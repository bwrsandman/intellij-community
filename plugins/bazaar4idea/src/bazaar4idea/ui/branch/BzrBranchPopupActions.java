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

import bazaar4idea.BzrBranch;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.branch.BzrBrancher;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.validators.BzrNewBranchNameValidator;
import com.intellij.dvcs.ui.NewBranchAction;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Kirill Likhodedov
 */
class BzrBranchPopupActions {

  private final Project myProject;
  private final BzrRepository myRepository;

  BzrBranchPopupActions(Project project, BzrRepository repository) {
    myProject = project;
    myRepository = repository;
  }

  ActionGroup createActions(@Nullable DefaultActionGroup toInsert) {
    DefaultActionGroup popupGroup = new DefaultActionGroup(null, false);

    popupGroup.addAction(new BzrNewBranchAction(myProject,Collections.singletonList(myRepository)));
    popupGroup.addAction(new CheckoutRevisionActions(myProject, myRepository));

    if (toInsert != null) {
      popupGroup.addAll(toInsert);
    }

    popupGroup.addSeparator("Local Branches");
    List<BzrBranch> localBranches = new ArrayList<BzrBranch>(myRepository.getBranches().getLocalBranches());
    Collections.sort(localBranches);
    for (BzrBranch localBranch : localBranches) {
      if (!localBranch.equals(myRepository.getCurrentBranch())) { // don't show current branch in the list
        popupGroup.add(new LocalBranchActions(myProject, Collections.singletonList(myRepository), localBranch.getName(), myRepository));
      }
    }

    popupGroup.addSeparator("Remote Branches");
    List<BzrBranch> remoteBranches = new ArrayList<BzrBranch>(myRepository.getBranches().getRemoteBranches());
    Collections.sort(remoteBranches);
    for (BzrBranch remoteBranch : remoteBranches) {
      popupGroup.add(new RemoteBranchActions(myProject, Collections.singletonList(myRepository), remoteBranch.getName(), myRepository));
    }
    
    return popupGroup;
  }

  public static class BzrNewBranchAction extends NewBranchAction<BzrRepository> {

    public BzrNewBranchAction(@NotNull Project project, @NotNull List<BzrRepository> repositories) {
      super(project, repositories);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      final String name = BzrBranchUtil.getNewBranchNameFromUser(myProject, myRepositories, "Create New Branch");
      if (name != null) {
        BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
        brancher.checkoutNewBranch(name, myRepositories);
      }
    }
  }

  /**
   * Checkout manually entered tag or revision number.
   */
  private static class CheckoutRevisionActions extends DumbAwareAction {
    private final Project myProject;
    private final BzrRepository myRepository;

    CheckoutRevisionActions(Project project, BzrRepository repository) {
      super("Checkout Tag or Revision");
      myProject = project;
      myRepository = repository;
    }

    @Override public void actionPerformed(AnActionEvent e) {
      // TODO autocomplete branches, tags.
      // on type check ref validity, on OK check ref existence.
      String reference = Messages
        .showInputDialog(myProject, "Enter reference (branch, tag) name or commit hash", "Checkout", Messages.getQuestionIcon());
      if (reference != null) {
        BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
        brancher.checkout(reference, Collections.singletonList(myRepository), null);
      }
    }

    @Override
    public void update(AnActionEvent e) {
      if (myRepository.isFresh()) {
        e.getPresentation().setEnabled(false);
        e.getPresentation().setDescription("Checkout is not possible before the first commit.");
      }
    }
  }

  /**
   * Actions available for local branches.
   */
  static class LocalBranchActions extends ActionGroup {

    private final Project myProject;
    private final List<BzrRepository> myRepositories;
    private String myBranchName;
    @NotNull private final BzrRepository mySelectedRepository;

    LocalBranchActions(@NotNull Project project, @NotNull List<BzrRepository> repositories, @NotNull String branchName,
                       @NotNull BzrRepository selectedRepository) {
      super("", true);
      myProject = project;
      myRepositories = repositories;
      myBranchName = branchName;
      mySelectedRepository = selectedRepository;
      getTemplatePresentation().setText(calcBranchText(), false); // no mnemonics
    }

    @NotNull
    private String calcBranchText() {
      String trackedBranch = new BzrMultiRootBranchConfig(myRepositories).getTrackedBranch(myBranchName);
      if (trackedBranch != null) {
        return myBranchName + " -> " + trackedBranch;
      }
      else {
        return myBranchName;
      }
    }

    @NotNull
    List<BzrRepository> getRepositories() {
      return myRepositories;
    }

    @NotNull
    public String getBranchName() {
      return myBranchName;
    }

    @NotNull
    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
      return new AnAction[] {
        new CheckoutAction(myProject, myRepositories, myBranchName),
        new CheckoutAsNewBranch(myProject, myRepositories, myBranchName),
        new CompareAction(myProject, myRepositories, myBranchName, mySelectedRepository),
        new MergeAction(myProject, myRepositories, myBranchName, true),
        new DeleteAction(myProject, myRepositories, myBranchName)
      };
    }

    private static class CheckoutAction extends DumbAwareAction {
      private final Project myProject;
      private final List<BzrRepository> myRepositories;
      private final String myBranchName;

      CheckoutAction(@NotNull Project project, @NotNull List<BzrRepository> repositories, @NotNull String branchName) {
        super("Checkout");
        myProject = project;
        myRepositories = repositories;
        myBranchName = branchName;
      }

      @Override
      public void actionPerformed(AnActionEvent e) {
        BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
        brancher.checkout(myBranchName, myRepositories, null);
      }

    }

    private static class CheckoutAsNewBranch extends DumbAwareAction {
      private final Project myProject;
      private final List<BzrRepository> myRepositories;
      private final String myBranchName;

      CheckoutAsNewBranch(@NotNull Project project, @NotNull List<BzrRepository> repositories, @NotNull String branchName) {
        super("Checkout as new branch");
        myProject = project;
        myRepositories = repositories;
        myBranchName = branchName;
      }

      @Override
      public void actionPerformed(AnActionEvent e) {
        final String name = Messages
          .showInputDialog(myProject, "Enter name of new branch", "Checkout New Branch From " + myBranchName,
                           Messages.getQuestionIcon(), "", BzrNewBranchNameValidator.newInstance(myRepositories));
        if (name != null) {
          BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
          brancher.checkoutNewBranchStartingFrom(name, myBranchName, myRepositories, null);
        }
      }

    }

    private static class DeleteAction extends DumbAwareAction {
      private final Project myProject;
      private final List<BzrRepository> myRepositories;
      private final String myBranchName;

      DeleteAction(Project project, List<BzrRepository> repositories, String branchName) {
        super("Delete");
        myProject = project;
        myRepositories = repositories;
        myBranchName = branchName;
      }

      @Override
      public void actionPerformed(AnActionEvent e) {
        BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
        brancher.deleteBranch(myBranchName, myRepositories);
      }
    }
  }

  /**
   * Actions available for remote branches
   */
  static class RemoteBranchActions extends ActionGroup {

    private final Project myProject;
    private final List<BzrRepository> myRepositories;
    private String myBranchName;
    @NotNull private final BzrRepository mySelectedRepository;

    RemoteBranchActions(@NotNull Project project, @NotNull List<BzrRepository> repositories, @NotNull String branchName,
                        @NotNull BzrRepository selectedRepository) {
      super("", true);
      myProject = project;
      myRepositories = repositories;
      myBranchName = branchName;
      mySelectedRepository = selectedRepository;
      getTemplatePresentation().setText(myBranchName, false); // no mnemonics
    }

    @NotNull
    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
      return new AnAction[] {
        new CheckoutRemoteBranchAction(myProject, myRepositories, myBranchName),
        new CompareAction(myProject, myRepositories, myBranchName, mySelectedRepository),
        new MergeAction(myProject, myRepositories, myBranchName, false),
        new RemoteDeleteAction(myProject, myRepositories, myBranchName)
      };
    }

    private static class CheckoutRemoteBranchAction extends DumbAwareAction {
      private final Project myProject;
      private final List<BzrRepository> myRepositories;
      private final String myRemoteBranchName;

      public CheckoutRemoteBranchAction(@NotNull Project project, @NotNull List<BzrRepository> repositories,
                                        @NotNull String remoteBranchName) {
        super("Checkout as new local branch");
        myProject = project;
        myRepositories = repositories;
        myRemoteBranchName = remoteBranchName;
      }

      @Override
      public void actionPerformed(AnActionEvent e) {
        final String name = Messages.showInputDialog(myProject, "Enter name of new branch", "Checkout Remote Branch", Messages.getQuestionIcon(),
                                               guessBranchName(), BzrNewBranchNameValidator.newInstance(myRepositories));
        if (name != null) {
          BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
          brancher.checkoutNewBranchStartingFrom(name, myRemoteBranchName, myRepositories, null);
        }
      }

      private String guessBranchName() {
        // TODO: check if we already have a branch with that name; check if that branch tracks this remote branch. Show different messages
        int slashPosition = myRemoteBranchName.indexOf("/");
        // if no slash is found (for example, in the case of git-svn remote branches), propose the whole name.
        return myRemoteBranchName.substring(slashPosition+1);
      }
    }

    private static class RemoteDeleteAction extends DumbAwareAction {
      private final Project myProject;
      private final List<BzrRepository> myRepositories;
      private final String myBranchName;

      RemoteDeleteAction(@NotNull Project project, @NotNull List<BzrRepository> repositories, @NotNull String branchName) {
        super("Delete");
        myProject = project;
        myRepositories = repositories;
        myBranchName = branchName;
      }

      @Override
      public void actionPerformed(AnActionEvent e) {
        BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
        brancher.deleteRemoteBranch(myBranchName, myRepositories);
      }
    }

  }
  
  private static class CompareAction extends DumbAwareAction {

    private final Project myProject;
    private final List<BzrRepository> myRepositories;
    private final String myBranchName;
    private final BzrRepository mySelectedRepository;

    public CompareAction(@NotNull Project project, @NotNull List<BzrRepository> repositories, @NotNull String branchName,
                         @NotNull BzrRepository selectedRepository) {
      super("Compare");
      myProject = project;
      myRepositories = repositories;
      myBranchName = branchName;
      mySelectedRepository = selectedRepository;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
      brancher.compare(myBranchName, myRepositories, mySelectedRepository);
    }

  }

  private static class MergeAction extends DumbAwareAction {

    private final Project myProject;
    private final List<BzrRepository> myRepositories;
    private final String myBranchName;
    private final boolean myLocalBranch;

    public MergeAction(@NotNull Project project, @NotNull List<BzrRepository> repositories, @NotNull String branchName,
                       boolean localBranch) {
      super("Merge");
      myProject = project;
      myRepositories = repositories;
      myBranchName = branchName;
      myLocalBranch = localBranch;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
      brancher.merge(myBranchName, deleteOnMerge(), myRepositories);
    }

    private BzrBrancher.DeleteOnMergeOption deleteOnMerge() {
      if (myLocalBranch && !myBranchName.equals("master")) {
        return BzrBrancher.DeleteOnMergeOption.PROPOSE;
      }
      return BzrBrancher.DeleteOnMergeOption.NOTHING;
    }
  }
}
