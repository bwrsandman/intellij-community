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

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrUtil;
import bazaar4idea.config.UpdateMethod;
import bazaar4idea.repo.BzrRepository;
import com.intellij.dvcs.repo.RepositoryUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static bazaar4idea.util.BzrUIUtil.code;

/**
 * @author Kirill Likhodedov
 */
class BzrRejectedPushUpdateDialog extends DialogWrapper {

  static final int MERGE_EXIT_CODE = NEXT_USER_EXIT_CODE;
  static final int REBASE_EXIT_CODE = MERGE_EXIT_CODE + 1;

  private static final String HTML_IDENT = "&nbsp;&nbsp;&nbsp;&nbsp;";
  public static final String DESCRIPTION_START = "<html>Push of current branch ";
  public static final String DESCRIPTION_ENDING =
    "Remote changes need to be merged before pushing.<br/>To push anyway you can merge or rebase now.</html>";

  private final Project myProject;
  private final Collection<BzrRepository> myRepositories;
  private final JCheckBox myUpdateAllRoots;
  private final RebaseAction myRebaseAction;
  private final MergeAction myMergeAction;
  private final JCheckBox myAutoUpdateInFuture;

  protected BzrRejectedPushUpdateDialog(@NotNull Project project,
                                        @NotNull Collection<BzrRepository> repositories,
                                        @NotNull BzrPusher.UpdateSettings initialSettings) {
    super(project);
    myProject = project;
    myRepositories = repositories;

    myUpdateAllRoots = new JCheckBox("Update not rejected repositories as well", initialSettings.shouldUpdateAllRoots());
    myUpdateAllRoots.setMnemonic('u');
    myAutoUpdateInFuture = new JCheckBox("<html>Remember the update method choice and <u>s</u>ilently update in future <br/>(you may change this in the Settings)</html>");
    myAutoUpdateInFuture.setMnemonic('s');

    myMergeAction = new MergeAction(this);
    myRebaseAction = new RebaseAction(this);
    getDefaultAction(initialSettings.getUpdateMethod()).putValue(DEFAULT_ACTION, Boolean.TRUE);
    getCancelAction().putValue(FOCUSED_ACTION, Boolean.TRUE);
    
    init();
    setTitle("Push Rejected");
  }

  private AbstractAction getDefaultAction(@Nullable UpdateMethod updateMethod) {
    if (updateMethod == UpdateMethod.REBASE) {
      return myRebaseAction;
    }
    return myMergeAction;
  }

  @Override
  protected JComponent createCenterPanel() {
    JBLabel desc = new JBLabel(makeDescription());

    JPanel options = new JPanel(new BorderLayout());
    options.add(myAutoUpdateInFuture, BorderLayout.SOUTH);
    
    if (!BzrUtil.justOneBzrRepository(myProject)) {
      options.add(myUpdateAllRoots);
    }

    final int GAP = 15;
    JPanel rootPanel = new JPanel(new BorderLayout(GAP, GAP));
    rootPanel.add(desc);
    rootPanel.add(options, BorderLayout.SOUTH);
    JLabel iconLabel = new JLabel(UIUtil.getQuestionIcon());
    rootPanel.add(iconLabel, BorderLayout.WEST);

    return rootPanel;
  }

  @Override
  protected String getHelpId() {
    return "reference.VersionControl.Bazaar.UpdateOnRejectedPushDialog";
  }

  private String makeDescription() {
    if (BzrUtil.justOneBzrRepository(myProject)) {
      assert !myRepositories.isEmpty() : "repositories are empty";
      BzrRepository repository = myRepositories.iterator().next();
      BzrBranch currentBranch = getCurrentBranch(repository);
      return DESCRIPTION_START + code(currentBranch.getName()) + " was rejected. <br/>" + DESCRIPTION_ENDING;
    }
    else if (myRepositories.size() == 1) {  // there are more than 1 repositories in the project, but only one was rejected
      BzrRepository repository = myRepositories.iterator().next();
      BzrBranch currentBranch = getCurrentBranch(repository);

      return DESCRIPTION_START + code(currentBranch.getName()) + " in repository <br/>" + code(repository.getPresentableUrl()) + " was rejected. <br/>" +
             DESCRIPTION_ENDING;
    }
    else {  // several repositories rejected the push
      Map<BzrRepository, BzrBranch> currentBranches = getCurrentBranches();
      if (allBranchesHaveTheSameName(currentBranches)) {
        String branchName = currentBranches.values().iterator().next().getName(); 
        StringBuilder sb = new StringBuilder(DESCRIPTION_START + code(branchName) + " was rejected in repositories <br/>");
        for (BzrRepository repository : RepositoryUtil.sortRepositories(currentBranches.keySet())) {
          sb.append(HTML_IDENT).append(code(repository.getPresentableUrl())).append("<br/>");
        }
        sb.append(DESCRIPTION_ENDING);
        return sb.toString();
      }
      else {
        StringBuilder sb = new StringBuilder("<html>Push of current branch was rejected: <br/>");
        for (Map.Entry<BzrRepository, BzrBranch> entry : currentBranches.entrySet()) {
          BzrRepository repository = entry.getKey();
          BzrBranch currentBranch = entry.getValue();
          sb.append(HTML_IDENT + code(currentBranch.getName()) + " in " + code(repository.getPresentableUrl()) + "<br/>");
        }
        sb.append(DESCRIPTION_ENDING);
        return sb.toString();
      }
    }
  }

  private static boolean allBranchesHaveTheSameName(@NotNull Map<BzrRepository, BzrBranch> branches) {
    String name = null;
    for (BzrBranch branch : branches.values()) {
      if (name == null) {
        name = branch.getName();
      } else if (!name.equals(branch.getName())) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  private Map<BzrRepository, BzrBranch> getCurrentBranches() {
    Map<BzrRepository, BzrBranch> currentBranches = new HashMap<BzrRepository, BzrBranch>();
    for (BzrRepository repository : myRepositories) {
      currentBranches.put(repository, getCurrentBranch(repository));
    }
    return currentBranches;
  }

  @NotNull
  private static BzrBranch getCurrentBranch(BzrRepository repository) {
    BzrBranch currentBranch = repository.getCurrentBranch();
    assert currentBranch != null : "Current branch can't be null here. " + repository;
    return currentBranch;
  }

  @NotNull
  @Override
  protected Action[] createActions() {
    return new Action[] { getCancelAction(), myMergeAction, myRebaseAction};
  }

  boolean shouldUpdateAll() {
    return myUpdateAllRoots.isSelected();
  }

  boolean shouldAutoUpdateInFuture() {
    return myAutoUpdateInFuture.isSelected();
  }

  private static class MergeAction extends AbstractAction {
    private final DialogWrapper myDialog;

    MergeAction(DialogWrapper dialog) {
      super("&Merge");
      myDialog = dialog;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      myDialog.close(MERGE_EXIT_CODE);
    }
  }

  private static class RebaseAction extends AbstractAction {
    private final DialogWrapper myDialog;

    RebaseAction(DialogWrapper dialog) {
      super("&Rebase");
      myDialog = dialog;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      myDialog.close(REBASE_EXIT_CODE);
    }
  }

}
