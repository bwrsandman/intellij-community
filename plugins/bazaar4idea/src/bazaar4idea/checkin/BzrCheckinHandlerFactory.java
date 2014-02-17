/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package bazaar4idea.checkin;

import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.CommitExecutor;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PairConsumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prohibits committing with an empty messages, warns if committing into detached HEAD, checks if user name and correct CRLF attributes
 * are set.
 * @author Kirill Likhodedov
*/
public class BzrCheckinHandlerFactory extends VcsCheckinHandlerFactory {

  private static final Logger LOG = Logger.getInstance(BzrCheckinHandlerFactory.class);

  public BzrCheckinHandlerFactory() {
    super(BzrVcs.getKey());
  }

  @NotNull
  @Override
  protected CheckinHandler createVcsHandler(final CheckinProjectPanel panel) {
    return new MyCheckinHandler(panel);
  }

  private class MyCheckinHandler extends CheckinHandler {
    @NotNull private final CheckinProjectPanel myPanel;
    @NotNull private final Project myProject;


    public MyCheckinHandler(@NotNull CheckinProjectPanel panel) {
      myPanel = panel;
      myProject = myPanel.getProject();
    }

    @Override
    public ReturnResult beforeCheckin(@Nullable CommitExecutor executor, PairConsumer<Object, Object> additionalDataConsumer) {
      if (emptyCommitMessage()) {
        return ReturnResult.CANCEL;
      }

      if (commitOrCommitAndPush(executor)) {
        ReturnResult result = checkUserName();
        if (result != ReturnResult.COMMIT) {
          return result;
        }
      }
      return ReturnResult.COMMIT;
    }

    private ReturnResult checkUserName() {
      Project project = myPanel.getProject();
      BzrVcs vcs = BzrVcs.getInstance(project);
      assert vcs != null;

      Collection<VirtualFile> notDefined = new ArrayList<VirtualFile>();
      Map<VirtualFile, Pair<String, String>> defined = new HashMap<VirtualFile, Pair<String, String>>();
      Collection<VirtualFile> allRoots = new ArrayList<VirtualFile>(Arrays.asList(
        ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs)));

      Collection<VirtualFile> affectedRoots = getSelectedRoots();
      for (VirtualFile root : affectedRoots) {
        try {
          Pair<String, String> nameAndEmail = BzrConfigUtil.getUserNameAndEmailFromBzrConfig(project, root);
          String name = nameAndEmail.getFirst();
          String email = nameAndEmail.getSecond();
          if (name == null || email == null) {
            notDefined.add(root);
          }
          else {
            defined.put(root, nameAndEmail);
          }
        }
        catch (VcsException e) {
          LOG.error("Couldn't get user.name and user.email for root " + root, e);
          // doing nothing - let commit with possibly empty user.name/email
        }
      }

      if (notDefined.isEmpty()) {
        return ReturnResult.COMMIT;
      }

      if (defined.isEmpty() && allRoots.size() > affectedRoots.size()) {
        allRoots.removeAll(affectedRoots);
        for (VirtualFile root : allRoots) {
          try {
            Pair<String, String> nameAndEmail = BzrConfigUtil.getUserNameAndEmailFromBzrConfig(project, root);
            String name = nameAndEmail.getFirst();
            String email = nameAndEmail.getSecond();
            if (name != null && email != null) {
              defined.put(root, nameAndEmail);
              break;
            }
          }
          catch (VcsException e) {
            LOG.error("Couldn't get user.name and user.email for root " + root, e);
            // doing nothing - not critical not to find the values for other roots not affected by commit
          }
        }
      }

      BzrUserNameNotDefinedDialog dialog = new BzrUserNameNotDefinedDialog(project, notDefined, affectedRoots, defined);
      dialog.show();
      if (dialog.isOK()) {
        try {
          if (dialog.isGlobal()) {
            BzrConfigUtil.setValue(project, notDefined.iterator().next(), BzrConfigUtil.USER_EMAIL, dialog.getUserEmail(), "--global");
          }
          else {
            for (VirtualFile root : notDefined) {
              BzrConfigUtil.setValue(project, root, BzrConfigUtil.USER_EMAIL, dialog.getUserEmail());
            }
          }
        }
        catch (VcsException e) {
          String message = "Couldn't set user.name and user.email";
          LOG.error(message, e);
          Messages.showErrorDialog(myPanel.getComponent(), message);
          return ReturnResult.CANCEL;
        }
        return ReturnResult.COMMIT;
      }
      return ReturnResult.CLOSE_WINDOW;
    }

    private boolean emptyCommitMessage() {
      if (myPanel.getCommitMessage().trim().isEmpty()) {
        Messages.showMessageDialog(myPanel.getComponent(), BzrBundle.message("bzr.commit.message.empty"),
                                   BzrBundle.message("bzr.commit.message.empty.title"), Messages.getErrorIcon());
        return true;
      }
      return false;
    }

    private boolean commitOrCommitAndPush(@Nullable CommitExecutor executor) {
      return executor == null || executor instanceof BzrCommitAndPushExecutor;
    }

    private String readMore(String link, String message) {
      if (Messages.canShowMacSheetPanel()) {
        return message + ":\n" + link;
      }
      else {
        return String.format("<a href='%s'>%s</a>.", link, message);
      }
    }

    /**
     * Scans the Bazaar roots, selected for commit, for the root which is on a detached HEAD.
     * Returns null, if all repositories are on the branch.
     * There might be several detached repositories, - in that case only one is returned.
     * This is because the situation is very rare, while it requires a lot of additional effort of making a well-formed message.
     */
    @Nullable
    private DetachedRoot getDetachedRoot() {
      BzrRepositoryManager repositoryManager = BzrUtil.getRepositoryManager(myPanel.getProject());
      for (VirtualFile root : getSelectedRoots()) {
        BzrRepository repository = repositoryManager.getRepositoryForRoot(root);
        if (repository == null) {
          continue;
        }
        if (!repository.isOnBranch()) {
          return new DetachedRoot(root, repository.isRebaseInProgress());
        }
      }
      return null;
    }

    @NotNull
    private Collection<VirtualFile> getSelectedRoots() {
      ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
      Collection<VirtualFile> result = new HashSet<VirtualFile>();
      for (FilePath path : ChangesUtil.getPaths(myPanel.getSelectedChanges())) {
        VirtualFile root = vcsManager.getVcsRootFor(path);
        if (root != null) {
          result.add(root);
        }
      }
      return result;
    }

    private class DetachedRoot {
      final VirtualFile myRoot;
      final boolean myRebase; // rebase in progress, or just detached due to a checkout of a commit.

      public DetachedRoot(@NotNull VirtualFile root, boolean rebase) {
        myRoot = root;
        myRebase = rebase;
      }
    }

  }

}
