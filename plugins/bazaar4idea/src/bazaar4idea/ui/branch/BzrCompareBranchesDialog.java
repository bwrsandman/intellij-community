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
package bazaar4idea.ui.branch;

import bazaar4idea.BzrUtil;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.util.BzrCommitCompareInfo;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.TabbedPaneImpl;
import icons.Git4ideaIcons;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Dialog for comparing two Bazaar branches.
 * @author Kirill Likhodedov
 */
public class BzrCompareBranchesDialog extends DialogWrapper {

  private final Project myProject;
  private final String myBranchName;
  private final String myCurrentBranchName;
  private final BzrCommitCompareInfo myCompareInfo;
  private final BzrRepository myInitialRepo;
  private JPanel myLogPanel;

  public BzrCompareBranchesDialog(@NotNull Project project,
                                  @NotNull String branchName,
                                  @NotNull String currentBranchName,
                                  @NotNull BzrCommitCompareInfo compareInfo,
                                  @NotNull BzrRepository initialRepo) {
    super(project, false);
    myCurrentBranchName = currentBranchName;
    myCompareInfo = compareInfo;
    myProject = project;
    myBranchName = branchName;
    myInitialRepo = initialRepo;

    String rootString;
    if (compareInfo.getRepositories().size() == 1 && BzrUtil.getRepositoryManager(myProject).moreThanOneRoot()) {
      rootString = " in root " + DvcsUtil.getShortRepositoryName(initialRepo);
    }
    else {
      rootString = "";
    }
    setTitle(String.format("Comparing %s with %s%s", currentBranchName, branchName, rootString));
    setModal(false);
    init();
  }

  @Override
  protected JComponent createCenterPanel() {
    myLogPanel = new BzrCompareBranchesLogPanel(myProject, myBranchName, myCurrentBranchName, myCompareInfo, myInitialRepo);
    JPanel diffPanel = new BzrCompareBranchesDiffPanel(myProject, myBranchName, myCurrentBranchName, myCompareInfo);

    TabbedPaneImpl tabbedPane = new TabbedPaneImpl(SwingConstants.TOP);
    tabbedPane.addTab("Log", Git4ideaIcons.Branch, myLogPanel);
    tabbedPane.addTab("Diff", AllIcons.Actions.Diff, diffPanel);
    tabbedPane.setKeyboardNavigation(TabbedPaneImpl.DEFAULT_PREV_NEXT_SHORTCUTS);
    return tabbedPane;
  }

  // it is information dialog - no need to OK or Cancel. Close the dialog by clicking the cross button or pressing Esc.
  @NotNull
  @Override
  protected Action[] createActions() {
    return new Action[0];
  }

  @Override
  protected String getDimensionServiceKey() {
    return BzrCompareBranchesDialog.class.getName();
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myLogPanel;
  }
}
