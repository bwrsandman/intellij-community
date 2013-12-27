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
package bazaar4idea.merge;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrUtil;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.repo.BzrBranchTrackInfo;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.ide.util.ElementsChooser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ListCellRendererWrapper;
import bazaar4idea.BzrRemoteBranch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Bazaar pull dialog
 */
public class BzrPullDialog extends DialogWrapper {

  private static final Logger LOG = Logger.getInstance(BzrPullDialog.class);

  /**
   * root panel
   */
  private JPanel myPanel;
  /**
   * The selected git root
   */
  private JComboBox myBzrRoot;
  /**
   * Current branch label
   */
  private JLabel myCurrentBranch;
  /**
   * The merge strategy
   */
  private JComboBox myStrategy;
  /**
   * No commit option
   */
  private JCheckBox myNoCommitCheckBox;
  /**
   * Squash commit option
   */
  private JCheckBox mySquashCommitCheckBox;
  /**
   * No fast forward option
   */
  private JCheckBox myNoFastForwardCheckBox;
  /**
   * Add log info to commit option
   */
  private JCheckBox myAddLogInformationCheckBox;
  /**
   * Selected remote option
   */
  private JComboBox myRemote;
  /**
   * The branch chooser
   */
  private ElementsChooser<String> myBranchChooser;
  /**
   * The context project
   */
  private final Project myProject;
  private final BzrRepositoryManager myRepositoryManager;

  /**
   * A constructor
   *
   * @param project     a project to select
   * @param roots       a git repository roots for the project
   * @param defaultRoot a guessed default root
   */
  public BzrPullDialog(Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
    super(project, true);
    setTitle(BzrBundle.getString("pull.title"));
    myProject = project;
    myRepositoryManager = BzrUtil.getRepositoryManager(myProject);
    BzrUIUtil.setupRootChooser(myProject, roots, defaultRoot, myBzrRoot, myCurrentBranch);
    myBzrRoot.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        updateRemotes();
      }
    });
    setOKButtonText(BzrBundle.getString("pull.button"));
    updateRemotes();
    updateBranches();
    myRemote.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        updateBranches();
      }
    });
    final ElementsChooser.ElementsMarkListener<String> listener = new ElementsChooser.ElementsMarkListener<String>() {
      public void elementMarkChanged(final String element, final boolean isMarked) {
        validateDialog();
      }
    };
    myBranchChooser.addElementsMarkListener(listener);
    listener.elementMarkChanged(null, true);
    BzrUIUtil.imply(mySquashCommitCheckBox, true, myNoCommitCheckBox, true);
    BzrUIUtil.imply(mySquashCommitCheckBox, true, myAddLogInformationCheckBox, false);
    BzrUIUtil.exclusive(mySquashCommitCheckBox, true, myNoFastForwardCheckBox, true);
    BzrMergeUtil.setupStrategies(myBranchChooser, myStrategy);
    init();
  }

  /**
   * Validate dialog and enable buttons
   */
  private void validateDialog() {
    String selectedRemote = getRemote();
    if (StringUtil.isEmptyOrSpaces(selectedRemote)) {
      setOKActionEnabled(false);
      return;
    }
    setOKActionEnabled(myBranchChooser.getMarkedElements().size() != 0);
  }

  /**
   * @return a pull handler configured according to dialog options
   */
  public BzrLineHandler makeHandler(@NotNull String url) {
    BzrLineHandler h = new BzrLineHandler(myProject, gitRoot(), BzrCommand.PULL);
    // ignore merge failure for the pull
    h.ignoreErrorCode(1);
    h.setUrl(url);
    h.addProgressParameter();
    h.addParameters("--no-stat");
    if (myNoCommitCheckBox.isSelected()) {
      h.addParameters("--no-commit");
    }
    else {
      if (myAddLogInformationCheckBox.isSelected()) {
        h.addParameters("--log");
      }
    }
    if (mySquashCommitCheckBox.isSelected()) {
      h.addParameters("--squash");
    }
    if (myNoFastForwardCheckBox.isSelected()) {
      h.addParameters("--no-ff");
    }
    String strategy = (String)myStrategy.getSelectedItem();
    if (!BzrMergeUtil.DEFAULT_STRATEGY.equals(strategy)) {
      h.addParameters("--strategy", strategy);
    }
    h.addParameters("-v");
    h.addProgressParameter();

    final List<String> markedBranches = myBranchChooser.getMarkedElements();
    String remote = getRemote();
    LOG.assertTrue(remote != null, "Selected remote can't be null here.");
    // git pull origin master (remote branch name in the format local to that remote)
    h.addParameters(remote);
    for (String branch : markedBranches) {
      h.addParameters(removeRemotePrefix(branch, remote));
    }
    return h;
  }

  @NotNull
  private static String removeRemotePrefix(@NotNull String branch, @NotNull String remote) {
    String prefix = remote + "/";
    if (branch.startsWith(prefix)) {
      return branch.substring(prefix.length());
    }
    LOG.error(String.format("Remote branch name seems to be invalid. Branch: %s, remote: %s", branch, remote));
    return branch;
  }

  private void updateBranches() {
    String selectedRemote = getRemote();
    myBranchChooser.removeAllElements();

    if (selectedRemote == null) {
      return;
    }

    BzrRepository repository = getRepository();
    if (repository == null) {
      return;
    }

    BzrBranchTrackInfo trackInfo = BzrUtil.getTrackInfoForCurrentBranch(repository);
    String currentRemoteBranch = trackInfo == null ? null : trackInfo.getRemoteBranch().getNameForLocalOperations();
    List<BzrRemoteBranch> remoteBranches = new ArrayList<BzrRemoteBranch>(repository.getBranches().getRemoteBranches());
    Collections.sort(remoteBranches);
    for (BzrBranch remoteBranch : remoteBranches) {
      if (belongsToRemote(remoteBranch, selectedRemote)) {
        myBranchChooser.addElement(remoteBranch.getName(), remoteBranch.getName().equals(currentRemoteBranch));
      }
    }

    validateDialog();
  }

  private static boolean belongsToRemote(@NotNull BzrBranch branch, @NotNull String remote) {
    return branch.getName().startsWith(remote + "/");
  }

  /**
   * Update remotes for the git root
   */
  private void updateRemotes() {
    BzrRepository repository = getRepository();
    if (repository == null) {
      return;
    }

    BzrRemote currentRemote = getCurrentOrDefaultRemote(repository);
    myRemote.setRenderer(getBazaarRemoteListCellRenderer(currentRemote != null ? currentRemote.getName() : null));
    myRemote.removeAllItems();
    for (BzrRemote remote : repository.getRemotes()) {
      myRemote.addItem(remote);
    }
    myRemote.setSelectedItem(currentRemote);
  }

  /**
   * If the current branch is a tracking branch, returns its remote.
   * Otherwise tries to guess: if there is origin, returns origin, otherwise returns the first remote in the list.
   */
  @Nullable
  private static BzrRemote getCurrentOrDefaultRemote(@NotNull BzrRepository repository) {
    Collection<BzrRemote> remotes = repository.getRemotes();
    if (remotes.isEmpty()) {
      return null;
    }

    BzrBranchTrackInfo trackInfo = BzrUtil.getTrackInfoForCurrentBranch(repository);
    if (trackInfo != null) {
      return trackInfo.getRemote();
    }
    else {
      BzrRemote origin = getOriginRemote(remotes);
      if (origin != null) {
        return origin;
      }
      else {
        return remotes.iterator().next();
      }
    }
  }

  @Nullable
  private static BzrRemote getOriginRemote(@NotNull Collection<BzrRemote> remotes) {
    for (BzrRemote remote : remotes) {
      if (remote.getName().equals(BzrRemote.ORIGIN_NAME)) {
        return remote;
      }
    }
    return null;
  }

  @Nullable
  private BzrRepository getRepository() {
    VirtualFile root = gitRoot();
    BzrRepository repository = myRepositoryManager.getRepositoryForRoot(root);
    if (repository == null) {
      LOG.error("Repository is null for " + root);
      return null;
    }
    return repository;
  }

  /**
   * Create list cell renderer for remotes. It shows both name and url and highlights the default
   * remote for the branch with bold.
   *
   *
   * @param defaultRemote a default remote
   * @return a list cell renderer for virtual files (it renders presentable URL
   */
  public ListCellRendererWrapper<BzrRemote> getBazaarRemoteListCellRenderer(final String defaultRemote) {
    return new ListCellRendererWrapper<BzrRemote>() {
      @Override
      public void customize(final JList list, final BzrRemote remote, final int index, final boolean selected, final boolean hasFocus) {
        final String text;
        if (remote == null) {
          text = BzrBundle.getString("util.remote.renderer.none");
        }
        else if (".".equals(remote.getName())) {
          text = BzrBundle.getString("util.remote.renderer.self");
        }
        else {
          String key;
          if (defaultRemote != null && defaultRemote.equals(remote.getName())) {
            key = "util.remote.renderer.default";
          }
          else {
            key = "util.remote.renderer.normal";
          }
          text = BzrBundle.message(key, remote.getName(), remote.getFirstUrl());
        }
        setText(text);
      }
    };
  }

  /**
   * @return a currently selected git root
   */
  public VirtualFile gitRoot() {
    return (VirtualFile)myBzrRoot.getSelectedItem();
  }


  /**
   * Create branch chooser
   */
  private void createUIComponents() {
    myBranchChooser = new ElementsChooser<String>(true);
  }

  /**
   * {@inheritDoc}
   */
  protected JComponent createCenterPanel() {
    return myPanel;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getDimensionServiceKey() {
    return getClass().getName();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getHelpId() {
    return "reference.VersionControl.Bazaar.Pull";
  }

  @Nullable
  public String getRemote() {
    BzrRemote remote = (BzrRemote)myRemote.getSelectedItem();
    return remote == null ? null : remote.getName();
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myBranchChooser.getComponent();
  }
}
