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

import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.config.BzrVcsSettings;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.ui.BranchActionGroupPopup;
import com.intellij.dvcs.ui.RootAction;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Condition;
import com.intellij.ui.popup.list.ListPopupImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.util.List;

/**
 * <p>
 * The popup which allows to quickly switch and control Bazaar branches.
 * </p>
 * <p>
 * Use {@link #asListPopup()} to achieve the {@link ListPopup} itself.
 * </p>
 *
 * @author Kirill Likhodedov
 */
class BzrBranchPopup {

  private final Project myProject;
  private final BzrRepositoryManager myRepositoryManager;
  private final BzrVcsSettings myVcsSettings;
  private final BzrVcs myVcs;
  private final BzrMultiRootBranchConfig myMultiRootBranchConfig;

  private final BzrRepository myCurrentRepository;
  private final ListPopupImpl myPopup;

  ListPopup asListPopup() {
    return myPopup;
  }

  /**
   * @param currentRepository Current repository, which means the repository of the currently open or selected file.
   *                          In the case of synchronized branch operations current repository matter much less, but sometimes is used,
   *                          for example, it is preselected in the repositories combobox in the compare branches dialog.
   */
  static BzrBranchPopup getInstance(@NotNull Project project, @NotNull BzrRepository currentRepository) {
    return new BzrBranchPopup(project, currentRepository);
  }

  private BzrBranchPopup(@NotNull Project project, @NotNull BzrRepository currentRepository) {
    myProject = project;
    myCurrentRepository = currentRepository;
    myRepositoryManager = BzrUtil.getRepositoryManager(project);
    myVcs = BzrVcs.getInstance(project);
    myVcsSettings = BzrVcsSettings.getInstance(myProject);

    myMultiRootBranchConfig = new BzrMultiRootBranchConfig(myRepositoryManager.getRepositories());

    String title = createPopupTitle(currentRepository);

    Condition<AnAction> preselectActionCondition = new Condition<AnAction>() {
      @Override
      public boolean value(AnAction action) {
        if (action instanceof BzrBranchPopupActions.LocalBranchActions) {
          BzrBranchPopupActions.LocalBranchActions branchAction = (BzrBranchPopupActions.LocalBranchActions)action;
          String branchName = branchAction.getBranchName();

          String recentBranch;
          List<BzrRepository> repositories = branchAction.getRepositories();
          if (repositories.size() == 1) {
            recentBranch = myVcsSettings.getRecentBranchesByRepository().get(repositories.iterator().next().getRoot().getPath());
          }
          else {
            recentBranch = myVcsSettings.getRecentCommonBranch();
          }

          if (recentBranch != null && recentBranch.equals(branchName)) {
            return true;
          }
        }
        return false;
      }
    };

    myPopup = new BranchActionGroupPopup(title, project, preselectActionCondition, createActions());

    initBranchSyncPolicyIfNotInitialized();
    setCurrentBranchInfo();
    warnThatBranchesDivergedIfNeeded();
  }

  private void initBranchSyncPolicyIfNotInitialized() {
    if (myRepositoryManager.moreThanOneRoot() && myVcsSettings.getSyncSetting() == BzrBranchSyncSetting.NOT_DECIDED) {
      if (!myMultiRootBranchConfig.diverged()) {
        notifyAboutSyncedBranches();
        myVcsSettings.setSyncSetting(BzrBranchSyncSetting.SYNC);
      }
      else {
        myVcsSettings.setSyncSetting(BzrBranchSyncSetting.DONT);
      }
    }
  }

  @NotNull
  private String createPopupTitle(@NotNull BzrRepository currentRepository) {
    String title = "Bazaar Branches";
    if (myRepositoryManager.moreThanOneRoot() &&
        (myMultiRootBranchConfig.diverged() || myVcsSettings.getSyncSetting() == BzrBranchSyncSetting.DONT)) {
      title += " in " + DvcsUtil.getShortRepositoryName(currentRepository);
    }
    return title;
  }

  private void setCurrentBranchInfo() {
    String currentBranchText = "Current branch";
    if (myRepositoryManager.moreThanOneRoot()) {
      if (myMultiRootBranchConfig.diverged()) {
        currentBranchText += " in " + DvcsUtil.getShortRepositoryName(myCurrentRepository) + ": " +
                             BzrBranchUtil.getDisplayableBranchText(myCurrentRepository);
      }
      else {
        currentBranchText += ": " + myMultiRootBranchConfig.getCurrentBranch();
      }
    }
    else {
      currentBranchText += ": " + BzrBranchUtil.getDisplayableBranchText(myCurrentRepository);
    }
    myPopup.setAdText(currentBranchText, SwingConstants.CENTER);
  }

  private void notifyAboutSyncedBranches() {
    BzrVcs.IMPORTANT_ERROR_NOTIFICATION.createNotification("Synchronous branch control enabled",
      "You have several Bazaar roots in the project and they all are checked out at the same branch. " +
      "We've enabled synchronous branch control for the project. <br/>" +
      "If you wish to control branches in different roots separately, you may <a href='settings'>disable</a> the setting.",
      NotificationType.INFORMATION, new NotificationListener() {
      @Override public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
        if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
          ShowSettingsUtil.getInstance().showSettingsDialog(myProject, myVcs.getConfigurable().getDisplayName());
          if (myVcsSettings.getSyncSetting() == BzrBranchSyncSetting.DONT) {
            notification.expire();
          }
        }
      }
    }).notify(myProject);
  }

  private ActionGroup createActions() {
    DefaultActionGroup popupGroup = new DefaultActionGroup(null, false);

    BzrRepositoryManager repositoryManager = myRepositoryManager;
    if (repositoryManager.moreThanOneRoot()) {

      if (!myMultiRootBranchConfig.diverged() && userWantsSyncControl()) {
        fillWithCommonRepositoryActions(popupGroup, repositoryManager);
      }
      else {
        fillPopupWithCurrentRepositoryActions(popupGroup, createRepositoriesActions());
      }
    }
    else {
      fillPopupWithCurrentRepositoryActions(popupGroup, null);
    }

    popupGroup.addSeparator();
    return popupGroup;
  }

  private boolean userWantsSyncControl() {
    return (myVcsSettings.getSyncSetting() != BzrBranchSyncSetting.DONT);
  }

  private void fillWithCommonRepositoryActions(DefaultActionGroup popupGroup, BzrRepositoryManager repositoryManager) {
    List<BzrRepository> repositories = repositoryManager.getRepositories();
    String currentBranch = myMultiRootBranchConfig.getCurrentBranch();
    assert currentBranch != null : "Current branch can't be null if branches have not diverged";
    popupGroup.add(new BzrBranchPopupActions.BzrNewBranchAction(myProject, repositories));

    popupGroup.addAll(createRepositoriesActions());

    popupGroup.addSeparator("Common Local Branches");
    for (String branch : myMultiRootBranchConfig.getLocalBranches()) {
      if (!branch.equals(currentBranch)) {
        popupGroup.add(new BzrBranchPopupActions.LocalBranchActions(myProject, repositories, branch, myCurrentRepository));
      }
    }

    popupGroup.addSeparator("Common Remote Branches");
    for (String branch : myMultiRootBranchConfig.getRemoteBranches()) {
      popupGroup.add(new BzrBranchPopupActions.RemoteBranchActions(myProject, repositories, branch, myCurrentRepository));
    }
  }

  private void warnThatBranchesDivergedIfNeeded() {
    if (myRepositoryManager.moreThanOneRoot() && myMultiRootBranchConfig.diverged() && userWantsSyncControl()) {
      myPopup.setWarning("Branches have diverged");
    }
  }

  private DefaultActionGroup createRepositoriesActions() {
    DefaultActionGroup popupGroup = new DefaultActionGroup(null, false);
    popupGroup.addSeparator("Repositories");
    for (BzrRepository repository : myRepositoryManager.getRepositories()) {
      popupGroup.add(new RootAction<BzrRepository>(repository, highlightCurrentRepo() ? myCurrentRepository : null,
                                                   new BzrBranchPopupActions(repository.getProject(), repository).createActions(null),
                                                   BzrBranchUtil.getDisplayableBranchText(repository)));
    }
    return popupGroup;
  }

  private boolean highlightCurrentRepo() {
    return !userWantsSyncControl() || myMultiRootBranchConfig.diverged();
  }

  private void fillPopupWithCurrentRepositoryActions(@NotNull DefaultActionGroup popupGroup, @Nullable DefaultActionGroup actions) {
    popupGroup.addAll(new BzrBranchPopupActions(myCurrentRepository.getProject(), myCurrentRepository).createActions(actions));
  }
}
