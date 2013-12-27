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
package bazaar4idea.branch;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrVcs;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.commands.BzrCompoundResult;
import bazaar4idea.jgit.BzrHttpAdapter;
import bazaar4idea.push.BzrSimplePushResult;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.ui.branch.BzrMultiRootBranchConfig;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ui.UIUtil;
import bazaar4idea.Notificator;
import bazaar4idea.commands.Bzr;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Kirill Likhodedov
 */
class BzrDeleteRemoteBranchOperation extends BzrBranchOperation {
  private final String myBranchName;

  public BzrDeleteRemoteBranchOperation(@NotNull Project project,
                                        @NotNull BzrPlatformFacade facade,
                                        @NotNull Bzr bzr,
                                        @NotNull BzrBranchUiHandler handler,
                                        @NotNull List<BzrRepository> repositories,
                                        @NotNull String name) {
    super(project, facade, bzr, handler, repositories);
    myBranchName = name;
  }

  @Override
  protected void execute() {
    final Collection<BzrRepository> repositories = getRepositories();
    final Collection<String> trackingBranches = findTrackingBranches(myBranchName, repositories);
    String currentBranch = BzrBranchUtil.getCurrentBranchOrRev(repositories);
    boolean currentBranchTracksBranchToDelete = false;
    if (trackingBranches.contains(currentBranch)) {
      currentBranchTracksBranchToDelete = true;
      trackingBranches.remove(currentBranch);
    }

    final AtomicReference<DeleteRemoteBranchDecision> decision = new AtomicReference<DeleteRemoteBranchDecision>();
    final boolean finalCurrentBranchTracksBranchToDelete = currentBranchTracksBranchToDelete;
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        decision.set(confirmBranchDeletion(myBranchName, trackingBranches, finalCurrentBranchTracksBranchToDelete, repositories));
      }
    });


    if (decision.get().delete()) {
      boolean deletedSuccessfully = doDeleteRemote(myBranchName, repositories);
      if (deletedSuccessfully) {
        final Collection<String> successfullyDeletedLocalBranches = new ArrayList<String>(1);
        if (decision.get().deleteTracking()) {
          for (final String branch : trackingBranches) {
            getIndicator().setText("Deleting " + branch);
            new BzrDeleteBranchOperation(myProject, myFacade, myBzr, myUiHandler, repositories, branch) {
              @Override
              protected void notifySuccess(@NotNull String message) {
                // do nothing - will display a combo notification for all deleted branches below
                successfullyDeletedLocalBranches.add(branch);
              }
            }.execute();
          }
        }
        notifySuccessfulDeletion(myBranchName, successfullyDeletedLocalBranches);
      }
    }

  }

  @Override
  protected void rollback() {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public String getSuccessMessage() {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  protected String getRollbackProposal() {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  protected String getOperationName() {
    throw new UnsupportedOperationException();
  }

  @NotNull
  private static Collection<String> findTrackingBranches(@NotNull String remoteBranch, @NotNull Collection<BzrRepository> repositories) {
    return new BzrMultiRootBranchConfig(repositories).getTrackingBranches(remoteBranch);
  }

  private boolean doDeleteRemote(@NotNull String branchName, @NotNull Collection<BzrRepository> repositories) {
    BzrCompoundResult result = new BzrCompoundResult(myProject);
    for (BzrRepository repository : repositories) {
      Pair<String, String> pair = splitNameOfRemoteBranch(branchName);
      String remote = pair.getFirst();
      String branch = pair.getSecond();
      BzrCommandResult res = pushDeletion(repository, remote, branch);
      result.append(repository, res);
      repository.update();
    }
    if (!result.totalSuccess()) {
      Notificator.getInstance(myProject).notifyError("Failed to delete remote branch " + branchName,
                                                             result.getErrorOutputWithReposIndication());
    }
    return result.totalSuccess();
  }

  /**
   * Returns the remote and the "local" name of a remote branch.
   * Expects branch in format "origin/master", i.e. remote/branch
   */
  private static Pair<String, String> splitNameOfRemoteBranch(String branchName) {
    int firstSlash = branchName.indexOf('/');
    String remoteName = firstSlash > -1 ? branchName.substring(0, firstSlash) : branchName;
    String remoteBranchName = branchName.substring(firstSlash + 1);
    return Pair.create(remoteName, remoteBranchName);
  }

  @NotNull
  private BzrCommandResult pushDeletion(@NotNull BzrRepository repository, @NotNull String remoteName, @NotNull String branchName) {
    BzrRemote remote = getRemoteByName(repository, remoteName);
    if (remote == null) {
      String error = "Couldn't find remote by name: " + remoteName;
      LOG.error(error);
      return BzrCommandResult.error(error);
    }

    String remoteUrl = remote.getFirstUrl();
    if (remoteUrl == null) {
      LOG.warn("No urls are defined for remote: " + remote);
      return BzrCommandResult.error("There is no urls defined for remote " + remote.getName());
    }
    if (BzrHttpAdapter.shouldUseJGit(remoteUrl)) {
      String fullBranchName = branchName.startsWith(BzrBranch.REFS_HEADS_PREFIX) ? branchName : BzrBranch.REFS_HEADS_PREFIX + branchName;
      String spec = ":" + fullBranchName;
      BzrSimplePushResult simplePushResult = BzrHttpAdapter.push(repository, remote.getName(), remoteUrl, spec);
      return convertSimplePushResultToCommandResult(simplePushResult);
    }
    else {
      return pushDeletionNatively(repository, remoteName, remoteUrl, branchName);
    }
  }

  @NotNull
  private BzrCommandResult pushDeletionNatively(@NotNull BzrRepository repository, @NotNull String remoteName, @NotNull String url,
                                                @NotNull String branchName) {
    return myBzr.push(repository, remoteName, url,":" + branchName);
  }

  @NotNull
  private static BzrCommandResult convertSimplePushResultToCommandResult(@NotNull BzrSimplePushResult result) {
    boolean success = result.getType() == BzrSimplePushResult.Type.SUCCESS;
    return new BzrCommandResult(success, -1, success ? Collections.<String>emptyList() : Collections.singletonList(result.getOutput()),
                                success ? Collections.singletonList(result.getOutput()) : Collections.<String>emptyList(), null);
  }

  @Nullable
  private static BzrRemote getRemoteByName(@NotNull BzrRepository repository, @NotNull String remoteName) {
    for (BzrRemote remote : repository.getRemotes()) {
      if (remote.getName().equals(remoteName)) {
        return remote;
      }
    }
    return null;
  }

  private void notifySuccessfulDeletion(@NotNull String remoteBranchName, @NotNull Collection<String> localBranches) {
    String message = "";
    if (!localBranches.isEmpty()) {
      message = "Also deleted local " + StringUtil.pluralize("branch", localBranches.size()) + ": " + StringUtil.join(localBranches, ", ");
    }
    Notificator.getInstance(myProject).notify(BzrVcs.NOTIFICATION_GROUP_ID, "Deleted remote branch " + remoteBranchName,
                                                      message, NotificationType.INFORMATION);
  }

  private DeleteRemoteBranchDecision confirmBranchDeletion(@NotNull String branchName, @NotNull Collection<String> trackingBranches,
                                                           boolean currentBranchTracksBranchToDelete,
                                                           @NotNull Collection<BzrRepository> repositories) {
    String title = "Delete Remote Branch";
    String message = "Delete remote branch " + branchName;

    boolean delete;
    final boolean deleteTracking;
    if (trackingBranches.isEmpty()) {
      delete = Messages.showYesNoDialog(myProject, message, title, "Delete", "Cancel", Messages.getQuestionIcon()) == Messages.YES;
      deleteTracking = false;
    }
    else {
      if (currentBranchTracksBranchToDelete) {
        message += "\n\nCurrent branch " + BzrBranchUtil.getCurrentBranchOrRev(repositories) + " tracks " + branchName + " but won't be deleted.";
      }
      final String checkboxMessage;
      if (trackingBranches.size() == 1) {
        checkboxMessage = "Delete tracking local branch " + trackingBranches.iterator().next() + " as well";
      }
      else {
        checkboxMessage = "Delete tracking local branches " + StringUtil.join(trackingBranches, ", ");
      }

      final AtomicBoolean deleteChoice = new AtomicBoolean();
      delete = MessageDialogBuilder.yesNo(title, message).project(myProject).yesText("Delete").noText("Cancel").doNotAsk(new DialogWrapper.DoNotAskOption() {
        @Override
        public boolean isToBeShown() {
          return true;
        }

        @Override
        public void setToBeShown(boolean value, int exitCode) {
          deleteChoice.set(!value);
        }

        @Override
        public boolean canBeHidden() {
          return true;
        }

        @Override
        public boolean shouldSaveOptionsOnCancel() {
          return false;
        }

        @Override
        public String getDoNotShowMessage() {
          return checkboxMessage;
        }
      }).show() == Messages.YES;
      deleteTracking = deleteChoice.get();
    }
    return new DeleteRemoteBranchDecision(delete, deleteTracking);
  }

  private static class DeleteRemoteBranchDecision {
    private final boolean delete;
    private final boolean deleteTracking;

    private DeleteRemoteBranchDecision(boolean delete, boolean deleteTracking) {
      this.delete = delete;
      this.deleteTracking = deleteTracking;
    }

    public boolean delete() {
      return delete;
    }

    public boolean deleteTracking() {
      return deleteTracking;
    }
  }

}