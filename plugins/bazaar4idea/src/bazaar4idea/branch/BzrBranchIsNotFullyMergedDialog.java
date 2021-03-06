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
package bazaar4idea.branch;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrCommit;
import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.ui.BzrCommitListWithDiffPanel;
import bazaar4idea.ui.BzrRepositoryComboboxListCellRenderer;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ArrayUtil;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * This dialog is shown when user tries to delete a local branch, which is not fully merged to the current branch.
 * It shows the list of commits not merged to the current branch and the list of branches, which the given branch is merged to.
 *
 * @author Kirill Likhodedov
 */
public class BzrBranchIsNotFullyMergedDialog extends DialogWrapper {

  private static final Logger LOG = Logger.getInstance(BzrBranchIsNotFullyMergedDialog.class);

  private final Project myProject;
  private final Map<BzrRepository, List<BzrCommit>> myCommits;
  private final String myBranchToDelete;
  private final String myBaseBranch;
  private final List<String> myMergedToBranches;

  private final BzrCommitListWithDiffPanel myCommitListWithDiffPanel;
  private final Collection<BzrRepository> myRepositories;
  private final BzrRepository myInitialRepository;

  /**
   * Show the dialog and get user's answer, whether he wants to force delete the branch.
   *
   * @param commits          the list of commits, which are not merged from the branch being deleted to the current branch,
   *                         grouped by repository.
   * @param branchToDelete   the name of the branch which user chose to delete.
   * @param mergedToBranches the list of branches which the branch is merged to (returned by {@code git branch --merged <branchToDelete>} command.
   * @param baseBranch       branch which branchToDelete is not merged to. It is either current branch, or the upstream branch.
   * @return true if user decided to delete the branch.
   */
  public static boolean showAndGetAnswer(@NotNull Project project,
                                         @NotNull Map<BzrRepository, List<BzrCommit>> commits,
                                         @NotNull String branchToDelete,
                                         @NotNull List<String> mergedToBranches,
                                         @Nullable String baseBranch) {
    BzrBranchIsNotFullyMergedDialog
      dialog = new BzrBranchIsNotFullyMergedDialog(project, commits, branchToDelete, baseBranch, mergedToBranches);
    ServiceManager.getService(project, BzrPlatformFacade.class).showDialog(dialog);
    return dialog.isOK();
  }

  private BzrBranchIsNotFullyMergedDialog(@NotNull Project project,
                                          @NotNull Map<BzrRepository, List<BzrCommit>> commits,
                                          @NotNull String branchToDelete,
                                          @Nullable String baseBranch,
                                          @NotNull List<String> mergedToBranches) {
    super(project, false);
    myProject = project;
    myCommits = commits;
    myBranchToDelete = branchToDelete;
    myBaseBranch = baseBranch;
    myMergedToBranches = mergedToBranches;
    myRepositories = commits.keySet();

    myInitialRepository = calcInitiallySelectedRepository();
    myCommitListWithDiffPanel = new BzrCommitListWithDiffPanel(myProject, new ArrayList<BzrCommit>(myCommits.get(myInitialRepository)));

    init();

    setTitle("Branch Is Not Fully Merged");
    setOKButtonText("Delete");
    setOKButtonMnemonic('D');
    setCancelButtonText("Cancel");
  }

  @NotNull
  private BzrRepository calcInitiallySelectedRepository() {
    for (BzrRepository repository : myRepositories) {
      if (!myCommits.get(repository).isEmpty()) {
        return repository;
      }
    }
    throw new AssertionError("The dialog shouldn't be shown. Unmerged commits: " + myCommits);
  }

  private String makeDescription() {
    String currentBranchOrRev;
    boolean onBranch;
    if (myRepositories.size() > 1) {
      LOG.assertTrue(myBaseBranch != null, "Branches have unexpectedly diverged");
      currentBranchOrRev = myBaseBranch;
      onBranch = true;
    }
    else {
      BzrRepository repository = myInitialRepository;
      if (repository.isOnBranch()) {
        BzrBranch currentBranch = repository.getCurrentBranch();
        assert currentBranch != null;
        currentBranchOrRev = currentBranch.getName();
        onBranch = true;
      }
      else {
        currentBranchOrRev = repository.getCurrentRevision();
        onBranch = false;
      }
    }

    StringBuilder description = new StringBuilder();
    if (onBranch) {
      description.append(BzrBundle.message("branch.delete.not_fully_merged.description", myBranchToDelete, myBaseBranch));
    } else {
      description.append(
        BzrBundle.message("branch.delete.not_fully_merged.description.not_on_branch", myBranchToDelete, currentBranchOrRev, myBaseBranch));
    }
    if (!myMergedToBranches.isEmpty()) {
      String listOfMergedBranches = StringUtil.join(StringUtil.surround(ArrayUtil.toStringArray(myMergedToBranches), "<b>", "</b>"), ", ");
      description.append("<br>");
      if (myMergedToBranches.size() == 1) {
        description.append(BzrBundle.message("branch.delete.merged_to.one", myBranchToDelete, listOfMergedBranches));
      }
      else {
        description.append(BzrBundle.message("branch.delete.merged_to.many", myBranchToDelete, listOfMergedBranches));
      }
    }
    description.append("<br>").append(BzrBundle.message("branch.delete.warning", myBranchToDelete));
    return description.toString();
  }

  @Override
  protected JComponent createNorthPanel() {
    JBLabel descriptionLabel = new JBLabel(XmlStringUtil.wrapInHtml(makeDescription()));

      final JComboBox repositorySelector = new JComboBox(ArrayUtil.toObjectArray(myRepositories, BzrRepository.class));
    repositorySelector.setRenderer(new BzrRepositoryComboboxListCellRenderer(repositorySelector));
    repositorySelector.setSelectedItem(myInitialRepository);
    repositorySelector.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        List<BzrCommit> commits = myCommits.get((BzrRepository)repositorySelector.getSelectedItem());
        myCommitListWithDiffPanel.setCommits(new ArrayList<BzrCommit>(commits));
      }
    });

    JPanel repoSelectorPanel = new JPanel(new BorderLayout());
    JBLabel label = new JBLabel("Repository: ");
    label.setLabelFor(repoSelectorPanel);
    repoSelectorPanel.add(label, BorderLayout.WEST);
    repoSelectorPanel.add(repositorySelector);

    if (myRepositories.size() < 2) {
      repoSelectorPanel.setVisible(false);
    }

    JPanel northPanel = new JPanel(new BorderLayout());
    northPanel.add(descriptionLabel);
    northPanel.add(repoSelectorPanel, BorderLayout.SOUTH);
    return northPanel;
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel rootPanel = new JPanel(new BorderLayout());
    rootPanel.add(myCommitListWithDiffPanel);
    return rootPanel;
  }

  @NotNull
  @Override
  protected Action[] createLeftSideActions() {
    return new Action[] { getOKAction() };
  }

  @NotNull
  @Override
  protected Action[] createActions() {
    Action cancelAction = getCancelAction();
    cancelAction.putValue(DEFAULT_ACTION, Boolean.TRUE);
    return new Action[] { cancelAction };
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myCommitListWithDiffPanel.getPreferredFocusComponent();
  }

  @Override
  protected String getDimensionServiceKey() {
    return BzrBranchIsNotFullyMergedDialog.class.getName();
  }
}
