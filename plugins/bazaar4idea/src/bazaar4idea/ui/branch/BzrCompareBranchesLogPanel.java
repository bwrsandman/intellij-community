/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

import bazaar4idea.BzrCommit;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.ui.BzrCommitListPanel;
import bazaar4idea.ui.BzrRepositoryComboboxListCellRenderer;
import bazaar4idea.util.BzrCommitCompareInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowser;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Kirill Likhodedov
 */
class BzrCompareBranchesLogPanel extends JPanel {

  private final Project myProject;
  private final String myBranchName;
  private final String myCurrentBranchName;
  private final BzrCommitCompareInfo myCompareInfo;
  private final BzrRepository myInitialRepo;

  private BzrCommitListPanel myHeadToBranchListPanel;
  private BzrCommitListPanel myBranchToHeadListPanel;

  BzrCompareBranchesLogPanel(@NotNull Project project,
                             @NotNull String branchName,
                             @NotNull String currentBranchName,
                             @NotNull BzrCommitCompareInfo compareInfo,
                             @NotNull BzrRepository initialRepo) {
    super(new BorderLayout(UIUtil.DEFAULT_HGAP, UIUtil.DEFAULT_VGAP));
    myProject = project;
    myBranchName = branchName;
    myCurrentBranchName = currentBranchName;
    myCompareInfo = compareInfo;
    myInitialRepo = initialRepo;

    add(createNorthPanel(), BorderLayout.NORTH);
    add(createCenterPanel());
  }

  private JComponent createCenterPanel() {
    final ChangesBrowser changesBrowser = new ChangesBrowser(myProject, null, Collections.<Change>emptyList(), null, false, true,
                                                             null, ChangesBrowser.MyUseCase.COMMITTED_CHANGES, null);

    myHeadToBranchListPanel = new BzrCommitListPanel(getHeadToBranchCommits(myInitialRepo),
                                                     String.format("Branch %s is fully merged to %s", myBranchName, myCurrentBranchName));
    myBranchToHeadListPanel = new BzrCommitListPanel(getBranchToHeadCommits(myInitialRepo),
                                                     String.format("Branch %s is fully merged to %s", myCurrentBranchName, myBranchName));

    addSelectionListener(myHeadToBranchListPanel, myBranchToHeadListPanel, changesBrowser);
    addSelectionListener(myBranchToHeadListPanel, myHeadToBranchListPanel, changesBrowser);

    myHeadToBranchListPanel.registerDiffAction(changesBrowser.getDiffAction());
    myBranchToHeadListPanel.registerDiffAction(changesBrowser.getDiffAction());

    JPanel htb = layoutCommitListPanel(myCurrentBranchName, true);
    JPanel bth = layoutCommitListPanel(myCurrentBranchName, false);

    JPanel listPanel = null;
    switch (getInfoType()) {
      case HEAD_TO_BRANCH:
        listPanel = htb;
        break;
      case BRANCH_TO_HEAD:
        listPanel = bth;
        break;
      case BOTH:
        Splitter lists = new Splitter(true, 0.5f);
        lists.setFirstComponent(htb);
        lists.setSecondComponent(bth);
        listPanel = lists;
    }

    Splitter rootPanel = new Splitter(false, 0.7f);
    rootPanel.setSecondComponent(changesBrowser);
    rootPanel.setFirstComponent(listPanel);
    return rootPanel;
  }

  private JComponent createNorthPanel() {
    final JComboBox repoSelector = new JComboBox(ArrayUtil.toObjectArray(myCompareInfo.getRepositories(), BzrRepository.class));
    repoSelector.setRenderer(new BzrRepositoryComboboxListCellRenderer(repoSelector));
    repoSelector.setSelectedItem(myInitialRepo);

    repoSelector.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        BzrRepository selectedRepo = (BzrRepository)repoSelector.getSelectedItem();
        myHeadToBranchListPanel.setCommits(getHeadToBranchCommits(selectedRepo));
        myBranchToHeadListPanel.setCommits(getBranchToHeadCommits(selectedRepo));
      }
    });

    JPanel repoSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    JBLabel label = new JBLabel("Repository: ");
    label.setLabelFor(repoSelectorPanel);
    label.setDisplayedMnemonic(KeyEvent.VK_R);
    repoSelectorPanel.add(label);
    repoSelectorPanel.add(repoSelector);

    if (myCompareInfo.getRepositories().size() < 2) {
      repoSelectorPanel.setVisible(false);
    }
    return repoSelectorPanel;
  }

  private ArrayList<BzrCommit> getBranchToHeadCommits(BzrRepository selectedRepo) {
    return new ArrayList<BzrCommit>(myCompareInfo.getBranchToHeadCommits(selectedRepo));
  }

  private ArrayList<BzrCommit> getHeadToBranchCommits(BzrRepository selectedRepo) {
    return new ArrayList<BzrCommit>(myCompareInfo.getHeadToBranchCommits(selectedRepo));
  }

  private BzrCommitCompareInfo.InfoType getInfoType() {
    return myCompareInfo.getInfoType();
  }

  private static void addSelectionListener(@NotNull BzrCommitListPanel sourcePanel,
                                           @NotNull final BzrCommitListPanel otherPanel,
                                           @NotNull final ChangesBrowser changesBrowser) {
    sourcePanel.addListMultipleSelectionListener(new Consumer<java.util.List<Change>>() {
      @Override
      public void consume(List<Change> changes) {
        changesBrowser.setChangesToDisplay(changes);
        otherPanel.clearSelection();
      }
    });
  }

  private JPanel layoutCommitListPanel(@NotNull String currentBranch, boolean forward) {
    String desc = makeDescription(currentBranch, forward);

    JPanel bth = new JPanel(new BorderLayout());
    JBLabel descriptionLabel = new JBLabel(desc, UIUtil.ComponentStyle.SMALL);
    descriptionLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
    bth.add(descriptionLabel, BorderLayout.NORTH);
    bth.add(forward ? myHeadToBranchListPanel : myBranchToHeadListPanel);
    return bth;
  }

  private String makeDescription(@NotNull String currentBranch, boolean forward) {
    String firstBranch = forward ? currentBranch : myBranchName;
    String secondBranch = forward ? myBranchName : currentBranch;
    return String.format("<html>Commits that exist in <code><b>%s</b></code> but don't exist in <code><b>%s</b></code> (<code>git log %s..%s</code>):</html>",
                         secondBranch, firstBranch, firstBranch, secondBranch);
  }
}
