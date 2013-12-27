/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package bazaar4idea.rebase;

import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.merge.BzrMergeUtil;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.ui.BzrReferenceValidator;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import bazaar4idea.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * The dialog that allows initiating git rebase activity
 */
public class BzrRebaseDialog extends DialogWrapper {

  private static final Logger LOG = Logger.getInstance(BzrRebaseDialog.class);

  /**
   * Bazaar root selector
   */
  protected JComboBox myBzrRootComboBox;
  /**
   * The selector for branch to rebase
   */
  protected JComboBox myBranchComboBox;
  /**
   * The from branch combo box. This is used as base branch if different from onto branch
   */
  protected JComboBox myFromComboBox;
  /**
   * The validation button for from branch
   */
  private JButton myFromValidateButton;
  /**
   * The onto branch combobox.
   */
  protected JComboBox myOntoComboBox;
  /**
   * The validate button for onto branch
   */
  private JButton myOntoValidateButton;
  /**
   * Show tags in drop down
   */
  private JCheckBox myShowTagsCheckBox;
  /**
   * Merge strategy drop down
   */
  private JComboBox myMergeStrategyComboBox;
  /**
   * If selected, rebase is interactive
   */
  protected JCheckBox myInteractiveCheckBox;
  /**
   * No merges are performed if selected.
   */
  private JCheckBox myDoNotUseMergeCheckBox;
  /**
   * The root panel of the dialog
   */
  private JPanel myPanel;
  /**
   * If selected, remote branches are shown as well
   */
  protected JCheckBox myShowRemoteBranchesCheckBox;
  /**
   * Preserve merges checkbox
   */
  private JCheckBox myPreserveMergesCheckBox;
  /**
   * The current project
   */
  protected final Project myProject;
  /**
   * The list of local branches
   */
  protected final List<BzrBranch> myLocalBranches = new ArrayList<BzrBranch>();
  /**
   * The list of remote branches
   */
  protected final List<BzrBranch> myRemoteBranches = new ArrayList<BzrBranch>();
  /**
   * The current branch
   */
  protected BzrBranch myCurrentBranch;
  /**
   * The tags
   */
  protected final List<BzrTag> myTags = new ArrayList<BzrTag>();
  /**
   * The validator for onto field
   */
  private final BzrReferenceValidator myOntoValidator;
  /**
   * The validator for from field
   */
  private final BzrReferenceValidator myFromValidator;

  /**
   * A constructor
   *
   * @param project     a project to select
   * @param roots       a git repository roots for the project
   * @param defaultRoot a guessed default root
   */
  public BzrRebaseDialog(Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
    super(project, true);
    setTitle(BzrBundle.getString("rebase.title"));
    setOKButtonText(BzrBundle.getString("rebase.button"));
    init();
    myProject = project;
    final Runnable validateRunnable = new Runnable() {
      public void run() {
        validateFields();
      }
    };
    myOntoValidator = new BzrReferenceValidator(myProject, myBzrRootComboBox, BzrUIUtil.getTextField(myOntoComboBox), myOntoValidateButton,
                                                validateRunnable);
    myFromValidator = new BzrReferenceValidator(myProject, myBzrRootComboBox, BzrUIUtil.getTextField(myFromComboBox), myFromValidateButton,
                                                validateRunnable);
    BzrUIUtil.setupRootChooser(myProject, roots, defaultRoot, myBzrRootComboBox, null);
    myBzrRootComboBox.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        validateFields();
      }
    });
    setupBranches();
    setupStrategy();
    validateFields();
  }

  public BzrLineHandler handler() {
    BzrLineHandler h = new BzrLineHandler(myProject, gitRoot(), BzrCommand.REBASE);
    if (myInteractiveCheckBox.isSelected() && myInteractiveCheckBox.isEnabled()) {
      h.addParameters("-i");
    }
    h.addParameters("-v");
    if (!myDoNotUseMergeCheckBox.isSelected()) {
      if (myMergeStrategyComboBox.getSelectedItem().equals(BzrMergeUtil.DEFAULT_STRATEGY)) {
        h.addParameters("-m");
      }
      else {
        h.addParameters("-s", myMergeStrategyComboBox.getSelectedItem().toString());
      }
    }
    if (myPreserveMergesCheckBox.isSelected()) {
      h.addParameters("-p");
    }
    String from = BzrUIUtil.getTextField(myFromComboBox).getText();
    String onto = BzrUIUtil.getTextField(myOntoComboBox).getText();
    if (from.length() == 0) {
      h.addParameters(onto);
    }
    else {
      h.addParameters("--onto", onto, from);
    }
    final String selectedBranch = (String)myBranchComboBox.getSelectedItem();
    if (myCurrentBranch != null && !myCurrentBranch.getName().equals(selectedBranch)) {
      h.addParameters(selectedBranch);
    }
    return h;
  }

  /**
   * Setup strategy
   */
  private void setupStrategy() {
    for (String s : BzrMergeUtil.getMergeStrategies(1)) {
      myMergeStrategyComboBox.addItem(s);
    }
    myMergeStrategyComboBox.setSelectedItem(BzrMergeUtil.DEFAULT_STRATEGY);
    myDoNotUseMergeCheckBox.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        myMergeStrategyComboBox.setEnabled(!myDoNotUseMergeCheckBox.isSelected());
      }
    });
  }


  /**
   * Validate fields
   */
  private void validateFields() {
    if (BzrUIUtil.getTextField(myOntoComboBox).getText().length() == 0) {
      setErrorText(null);
      setOKActionEnabled(false);
      return;
    }
    else if (myOntoValidator.isInvalid()) {
      setErrorText(BzrBundle.getString("rebase.invalid.onto"));
      setOKActionEnabled(false);
      return;
    }
    if (BzrUIUtil.getTextField(myFromComboBox).getText().length() != 0 && myFromValidator.isInvalid()) {
      setErrorText(BzrBundle.getString("rebase.invalid.from"));
      setOKActionEnabled(false);
      return;
    }
    if (BzrRebaseUtils.isRebaseInTheProgress(gitRoot())) {
      setErrorText(BzrBundle.getString("rebase.in.progress"));
      setOKActionEnabled(false);
      return;
    }
    setErrorText(null);
    setOKActionEnabled(true);
  }

  /**
   * Setup branch drop down.
   */
  private void setupBranches() {
    BzrUIUtil.getTextField(myOntoComboBox).getDocument().addDocumentListener(new DocumentAdapter() {
      protected void textChanged(final DocumentEvent e) {
        validateFields();
      }
    });
    final ActionListener rootListener = new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        loadRefs();
        updateBranches();
      }
    };
    final ActionListener showListener = new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        updateOntoFrom();
      }
    };
    myShowRemoteBranchesCheckBox.addActionListener(showListener);
    myShowTagsCheckBox.addActionListener(showListener);
    rootListener.actionPerformed(null);
    myBzrRootComboBox.addActionListener(rootListener);
    myBranchComboBox.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        updateTrackedBranch();
      }
    });
  }

  /**
   * Update branches when git root changed
   */
  private void updateBranches() {
    myBranchComboBox.removeAllItems();
    for (BzrBranch b : myLocalBranches) {
      myBranchComboBox.addItem(b.getName());
    }
    if (myCurrentBranch != null) {
      myBranchComboBox.setSelectedItem(myCurrentBranch.getName());
    }
    else {
      myBranchComboBox.setSelectedItem(0);
    }
    updateOntoFrom();
    updateTrackedBranch();
  }

  /**
   * Update onto and from comboboxes.
   */
  protected void updateOntoFrom() {
    String onto = BzrUIUtil.getTextField(myOntoComboBox).getText();
    String from = BzrUIUtil.getTextField(myFromComboBox).getText();
    myFromComboBox.removeAllItems();
    myOntoComboBox.removeAllItems();
    for (BzrBranch b : myLocalBranches) {
      myFromComboBox.addItem(b);
      myOntoComboBox.addItem(b);
    }
    if (myShowRemoteBranchesCheckBox.isSelected()) {
      for (BzrBranch b : myRemoteBranches) {
        myFromComboBox.addItem(b);
        myOntoComboBox.addItem(b);
      }
    }
    if (myShowTagsCheckBox.isSelected()) {
      for (BzrTag t : myTags) {
        myFromComboBox.addItem(t);
        myOntoComboBox.addItem(t);
      }
    }
    BzrUIUtil.getTextField(myOntoComboBox).setText(onto);
    BzrUIUtil.getTextField(myFromComboBox).setText(from);
  }

  /**
   * Load tags and branches
   */
  protected void loadRefs() {
    try {
      myLocalBranches.clear();
      myRemoteBranches.clear();
      myTags.clear();
      final VirtualFile root = gitRoot();
      BzrRepository repository = BzrUtil.getRepositoryManager(myProject).getRepositoryForRoot(root);
      if (repository != null) {
        myLocalBranches.addAll(repository.getBranches().getLocalBranches());
        myRemoteBranches.addAll(repository.getBranches().getRemoteBranches());
        myCurrentBranch = repository.getCurrentBranch();
      }
      else {
        LOG.error("Repository is null for root " + root);
      }
      BzrTag.list(myProject, root, myTags);
    }
    catch (VcsException e) {
      BzrUIUtil.showOperationError(myProject, e, "git branch -a");
    }
  }

  /**
   * Update tracked branch basing on the currently selected branch
   */
  private void updateTrackedBranch() {
    try {
      final VirtualFile root = gitRoot();
      String currentBranch = (String)myBranchComboBox.getSelectedItem();
      BzrBranch trackedBranch = null;
      if (currentBranch != null) {
        String remote = BzrConfigUtil.getValue(myProject, root, "branch." + currentBranch + ".remote");
        String mergeBranch = BzrConfigUtil.getValue(myProject, root, "branch." + currentBranch + ".merge");
        if (remote == null || mergeBranch == null) {
          trackedBranch = null;
        }
        else {
          mergeBranch = BzrBranchUtil.stripRefsPrefix(mergeBranch);
          BzrRemote r = BzrBranchUtil.findRemoteByNameOrLogError(myProject, root, remote);
          if (r != null) {
            trackedBranch = new BzrStandardRemoteBranch(r, mergeBranch, BzrBranch.DUMMY_HASH);
          }
        }
      }
      if (trackedBranch != null) {
        myOntoComboBox.setSelectedItem(trackedBranch);
      }
      else {
        BzrUIUtil.getTextField(myOntoComboBox).setText("");
      }
      BzrUIUtil.getTextField(myFromComboBox).setText("");
    }
    catch (VcsException e) {
      BzrUIUtil.showOperationError(myProject, e, "bzr config");
    }
  }

  /**
   * @return the currently selected git root
   */
  public VirtualFile gitRoot() {
    return (VirtualFile)myBzrRootComboBox.getSelectedItem();
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
  protected JComponent createCenterPanel() {
    return myPanel;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getHelpId() {
    return "reference.VersionControl.Bazaar.Rebase";
  }
}
