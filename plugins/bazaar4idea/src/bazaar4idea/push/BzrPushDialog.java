/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bazaar4idea.push;

import bazaar4idea.*;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.util.Consumer;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Kirill Likhodedov
 */
public class BzrPushDialog extends DialogWrapper {

  private static final Logger LOG = Logger.getInstance(BzrPushDialog.class);
  private static final String DEFAULT_REMOTE = ":parent";

  private Project myProject;
  private final BzrRepositoryManager myRepositoryManager;
  private final BzrPusher myPusher;
  private final BzrPushLog myListPanel;
  private BzrCommitsByRepoAndBranch myBzrCommitsToPush;
  private Map<BzrRepository, BzrPushSpec> myPushSpecs;
  private final Collection<BzrRepository> myRepositories;
  private final JBLoadingPanel myLoadingPanel;
  private final Object COMMITS_LOADING_LOCK = new Object();
  private final BzrManualPushToBranch myRefspecPanel;
  private final AtomicReference<String> myDestBranchInfoOnRefresh = new AtomicReference<String>();

  private final boolean myPushPossible;

  public BzrPushDialog(@NotNull Project project) {
    super(project);
    myProject = project;
    myPusher = new BzrPusher(myProject, ServiceManager.getService(project, BzrPlatformFacade.class), new EmptyProgressIndicator());
    myRepositoryManager = BzrUtil.getRepositoryManager(myProject);

    myRepositories = getRepositoriesWithRemotes();

    myLoadingPanel = new JBLoadingPanel(new BorderLayout(), this.getDisposable());

    myListPanel = new BzrPushLog(myProject, myRepositories, new RepositoryCheckboxListener());
    myRefspecPanel = new BzrManualPushToBranch(myRepositories, new RefreshButtonListener());

    if (BzrManualPushToBranch.getRemotesWithCommonNames(myRepositories).isEmpty()) {
      myRefspecPanel.setVisible(false);
      setErrorText("Can't push, because no remotes are defined");
      setOKActionEnabled(false);
      myPushPossible = false;
    } else {
      myPushPossible = true;
    }

    init();
    setOKButtonText("Push");
    setOKButtonMnemonic('P');
    setTitle("Bazaar Push");
  }

  @NotNull
  private List<BzrRepository> getRepositoriesWithRemotes() {
    List<BzrRepository> repositories = new ArrayList<BzrRepository>();
    for (BzrRepository repository : myRepositoryManager.getRepositories()) {
      if (!repository.getRemotes().isEmpty()) {
        repositories.add(repository);
      }
    }
    return repositories;
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel optionsPanel = new JPanel(new BorderLayout());
    optionsPanel.add(myRefspecPanel);

    JComponent rootPanel = new JPanel(new BorderLayout(0, 15));
    rootPanel.add(createCommitListPanel(), BorderLayout.CENTER);
    rootPanel.add(optionsPanel, BorderLayout.SOUTH);
    return rootPanel;
  }

  @Override
  protected String getHelpId() {
    return "reference.VersionControl.Bazaar.PushDialog";
  }

  private JComponent createCommitListPanel() {
    myLoadingPanel.add(myListPanel, BorderLayout.CENTER);
    if (myPushPossible) {
      loadCommitsInBackground();
    } else {
      myLoadingPanel.startLoading();
      myLoadingPanel.stopLoading();
    }

    JPanel commitListPanel = new JPanel(new BorderLayout());
    commitListPanel.add(myLoadingPanel, BorderLayout.CENTER);
    return commitListPanel;
  }

  private void loadCommitsInBackground() {
    myLoadingPanel.startLoading();

    ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
      public void run() {
        final AtomicReference<String> error = new AtomicReference<String>();
        synchronized (COMMITS_LOADING_LOCK) {
          error.set(collectInfoToPush());
        }

        final Pair<String, String> remoteAndBranch = getRemoteAndTrackedBranchForCurrentBranch();
        UIUtil.invokeLaterIfNeeded(new Runnable() {
          @Override
          public void run() {
            if (error.get() != null) {
              myListPanel.displayError(error.get());
            } else {
              myListPanel.setCommits(myBzrCommitsToPush);
            }
            if (!myRefspecPanel.turnedOn()) {
              myRefspecPanel.selectRemote(remoteAndBranch.getFirst());
              myRefspecPanel.setBranchToPushIfNotSet(remoteAndBranch.getSecond());
            }
            myLoadingPanel.stopLoading();
          }
        });
      }
    });
  }

  @NotNull
  private Pair<String, String> getRemoteAndTrackedBranchForCurrentBranch() {
    if (myBzrCommitsToPush != null) {
      Collection<BzrRepository> repositories = myBzrCommitsToPush.getRepositories();
      if (!repositories.isEmpty()) {
        BzrRepository repository = repositories.iterator().next();
        BzrBranch currentBranch = repository.getCurrentBranch();
        assert currentBranch != null;
        if (myBzrCommitsToPush.get(repository).get(currentBranch).getDestBranch() == BzrPusher.NO_TARGET_BRANCH) { // push to branch with the same name
          return Pair.create(DEFAULT_REMOTE, currentBranch.getName());
        }
        String remoteName;
        try {
          remoteName = BzrBranchUtil.getTrackedRemoteName(myProject, repository.getRoot(), currentBranch.getName());
          if (remoteName == null) {
            remoteName = DEFAULT_REMOTE;
          }
        }
        catch (VcsException e) {
          LOG.info("Couldn't retrieve tracked branch for current branch " + currentBranch, e);
          remoteName = DEFAULT_REMOTE;
        }
        String targetBranch = myBzrCommitsToPush.get(repository).get(currentBranch).getDestBranch().getNameForRemoteOperations();
        return Pair.create(remoteName, targetBranch);
      }
    }
    return Pair.create(DEFAULT_REMOTE, "");
  }

  @Nullable
  private String collectInfoToPush() {
    try {
      LOG.info("collectInfoToPush...");
      myPushSpecs = pushSpecsForCurrentOrEnteredBranches();
      myBzrCommitsToPush = myPusher.collectCommitsToPush(myPushSpecs);
      LOG.info(String.format("collectInfoToPush | Collected commits to push. Push spec: %s, commits: %s",
                             myPushSpecs, logMessageForCommits(myBzrCommitsToPush)));
      return null;
    }
    catch (VcsException e) {
      myBzrCommitsToPush = BzrCommitsByRepoAndBranch.empty();
      LOG.error("collectInfoToPush | Couldn't collect commits to push. Push spec: " + myPushSpecs, e);
      return e.getMessage();
    }
  }

  private static String logMessageForCommits(BzrCommitsByRepoAndBranch commitsToPush) {
    StringBuilder logMessage = new StringBuilder();
    for (BzrCommit commit : commitsToPush.getAllCommits()) {
      logMessage.append(BzrUtil.getShortHash(commit.getHash().toString()));
    }
    return logMessage.toString();
  }

  private Map<BzrRepository, BzrPushSpec> pushSpecsForCurrentOrEnteredBranches() throws VcsException {
    Map<BzrRepository, BzrPushSpec> defaultSpecs = new HashMap<BzrRepository, BzrPushSpec>();
    for (BzrRepository repository : myRepositories) {
      BzrLocalBranch currentBranch = repository.getCurrentBranch();
      if (currentBranch == null) {
        continue;
      }
      String remoteName = BzrBranchUtil.getTrackedRemoteName(repository.getProject(), repository.getRoot(), currentBranch.getName());
      String trackedBranchName = BzrBranchUtil.getTrackedBranchName(repository.getProject(), repository.getRoot(), currentBranch.getName());
      BzrRemote remote = BzrUtil.findRemoteByName(repository, remoteName);
      BzrRemoteBranch targetBranch;
      if (remote != null && trackedBranchName != null) {
        targetBranch = BzrBranchUtil
          .findRemoteBranchByName(trackedBranchName, remote.getName(), repository.getBranches().getRemoteBranches());
      }
      else {
        Pair<BzrRemote, BzrRemoteBranch> remoteAndBranch = BzrUtil.findMatchingRemoteBranch(repository, currentBranch);
        if (remoteAndBranch == null) {
          targetBranch = BzrPusher.NO_TARGET_BRANCH;
        } else {
          targetBranch = remoteAndBranch.getSecond();
        }
      }

      if (myRefspecPanel.turnedOn()) {
        String manualBranchName = myRefspecPanel.getBranchToPush();
        remote = myRefspecPanel.getSelectedRemote();
        BzrRemoteBranch manualBranch = BzrBranchUtil
          .findRemoteBranchByName(manualBranchName, remote.getName(), repository.getBranches().getRemoteBranches());
        if (manualBranch == null) {
          manualBranch = new BzrStandardRemoteBranch(remote, manualBranchName, BzrBranch.DUMMY_HASH);
        }
        targetBranch = manualBranch;
      }

      BzrPushSpec pushSpec = new BzrPushSpec(currentBranch, targetBranch == null ? BzrPusher.NO_TARGET_BRANCH : targetBranch);
      defaultSpecs.put(repository, pushSpec);
    }
    return defaultSpecs;
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myListPanel.getPreferredFocusComponent();
  }

  @Override
  protected String getDimensionServiceKey() {
    return BzrPushDialog.class.getName();
  }

  @NotNull
  public BzrPushInfo getPushInfo() {
    // waiting for commit list loading, because this information is needed to correctly handle rejected push situation and correctly
    // notify about pushed commits
    // TODO optimize: don't refresh: information about pushed commits can be achieved from the successful push output
    LOG.info("getPushInfo start");
    synchronized (COMMITS_LOADING_LOCK) {
      BzrCommitsByRepoAndBranch selectedCommits;
      if (myBzrCommitsToPush == null) {
        LOG.info("getPushInfo | myBzrCommitsToPush == null. collecting...");
        collectInfoToPush();
        selectedCommits = myBzrCommitsToPush;
      }
      else {
        if (refreshNeeded()) {
          LOG.info("getPushInfo | refresh is needed, collecting...");
          collectInfoToPush();
        }
        Collection<BzrRepository> selectedRepositories = myListPanel.getSelectedRepositories();
        selectedCommits = myBzrCommitsToPush.retainAll(selectedRepositories);
      }
      LOG.info("getPushInfo | selectedCommits: " + logMessageForCommits(selectedCommits));
      return new BzrPushInfo(selectedCommits, myPushSpecs);
    }
  }

  private boolean refreshNeeded() {
    String currentDestBranchValue = myRefspecPanel.turnedOn() ? myRefspecPanel.getBranchToPush(): null;
    String savedValue = myDestBranchInfoOnRefresh.get();
    if (savedValue == null) {
      return currentDestBranchValue != null;
    }
    return !savedValue.equals(currentDestBranchValue);
  }

  private class RepositoryCheckboxListener implements Consumer<Boolean> {
    @Override public void consume(Boolean checked) {
      if (checked) {
        setOKActionEnabled(true);
      } else {
        Collection<BzrRepository> repositories = myListPanel.getSelectedRepositories();
        if (repositories.isEmpty()) {
          setOKActionEnabled(false);
        } else {
          setOKActionEnabled(true);
        }
      }
    }
  }

  private class RefreshButtonListener implements Runnable {
    @Override
    public void run() {
      myDestBranchInfoOnRefresh.set(myRefspecPanel.turnedOn() ? myRefspecPanel.getBranchToPush(): null);
      loadCommitsInBackground();
    }
  }

}
