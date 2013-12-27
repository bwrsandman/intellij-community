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
package bazaar4idea.branch;

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrVcs;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.util.BzrPreservingProcess;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import bazaar4idea.commands.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static bazaar4idea.util.BzrUIUtil.code;

/**
 * Represents {@code git checkout} operation.
 * Fails to checkout if there are unmerged files.
 * Fails to checkout if there are untracked files that would be overwritten by checkout. Shows the list of files.
 * If there are local changes that would be overwritten by checkout, proposes to perform a "smart checkout" which means stashing local
 * changes, checking out, and then unstashing the changes back (possibly with showing the conflict resolving dialog). 
 *
 *  @author Kirill Likhodedov
 */
class BzrCheckoutOperation extends BzrBranchOperation {

  public static final String ROLLBACK_PROPOSAL_FORMAT = "You may rollback (checkout back to %s) not to let branches diverge.";

  @NotNull private final String myStartPointReference;
  @Nullable private final String myNewBranch;

  BzrCheckoutOperation(@NotNull Project project,
                       BzrPlatformFacade facade,
                       @NotNull Bzr bzr,
                       @NotNull BzrBranchUiHandler uiHandler,
                       @NotNull Collection<BzrRepository> repositories,
                       @NotNull String startPointReference,
                       @Nullable String newBranch) {
    super(project, facade, bzr, uiHandler, repositories);
    myStartPointReference = startPointReference;
    myNewBranch = newBranch;
  }
  
  @Override
  protected void execute() {
    saveAllDocuments();
    boolean fatalErrorHappened = false;
    while (hasMoreRepositories() && !fatalErrorHappened) {
      final BzrRepository repository = next();

      VirtualFile root = repository.getRoot();
      BzrLocalChangesWouldBeOverwrittenDetector localChangesDetector =
        new BzrLocalChangesWouldBeOverwrittenDetector(root, BzrLocalChangesWouldBeOverwrittenDetector.Operation.CHECKOUT);
      BzrSimpleEventDetector unmergedFiles = new BzrSimpleEventDetector(BzrSimpleEventDetector.Event.UNMERGED_PREVENTING_CHECKOUT);
      BzrUntrackedFilesOverwrittenByOperationDetector untrackedOverwrittenByCheckout =
        new BzrUntrackedFilesOverwrittenByOperationDetector(root);

      BzrCommandResult result = myBzr.checkout(repository, myStartPointReference, myNewBranch, false,
                                             localChangesDetector, unmergedFiles, untrackedOverwrittenByCheckout);
      if (result.success()) {
        refresh(repository);
        markSuccessful(repository);
      }
      else if (unmergedFiles.hasHappened()) {
        fatalUnmergedFilesError();
        fatalErrorHappened = true;
      }
      else if (localChangesDetector.wasMessageDetected()) {
        boolean smartCheckoutSucceeded = smartCheckoutOrNotify(repository, localChangesDetector);
        if (!smartCheckoutSucceeded) {
          fatalErrorHappened = true;
        }
      }
      else if (untrackedOverwrittenByCheckout.wasMessageDetected()) {
        fatalUntrackedFilesError(untrackedOverwrittenByCheckout.getFiles());
        fatalErrorHappened = true;
      }
      else {
        fatalError(getCommonErrorTitle(), result.getErrorOutputAsJoinedString());
        fatalErrorHappened = true;
      }
    }

    if (!fatalErrorHappened) {
      notifySuccess();
      updateRecentBranch();
    }
  }

  private boolean smartCheckoutOrNotify(@NotNull BzrRepository repository,
                                        @NotNull BzrMessageWithFilesDetector localChangesOverwrittenByCheckout) {
    Pair<List<BzrRepository>, List<Change>> conflictingRepositoriesAndAffectedChanges =
      getConflictingRepositoriesAndAffectedChanges(repository, localChangesOverwrittenByCheckout, myCurrentBranchOrRev, myStartPointReference);
    List<BzrRepository> allConflictingRepositories = conflictingRepositoriesAndAffectedChanges.getFirst();
    List<Change> affectedChanges = conflictingRepositoriesAndAffectedChanges.getSecond();

    int smartCheckoutDecision = myUiHandler.showSmartOperationDialog(myProject, affectedChanges, "checkout", true);
    if (smartCheckoutDecision == BzrSmartOperationDialog.SMART_EXIT_CODE) {
      boolean smartCheckedOutSuccessfully = smartCheckout(allConflictingRepositories, myStartPointReference, myNewBranch, getIndicator());
      if (smartCheckedOutSuccessfully) {
        for (BzrRepository conflictingRepository : allConflictingRepositories) {
          markSuccessful(conflictingRepository);
          refresh(conflictingRepository);
        }
        return true;
      }
      else {
        // notification is handled in smartCheckout()
        return false;
      }
    }
    else if (smartCheckoutDecision == BzrSmartOperationDialog.FORCE_EXIT_CODE) {
      boolean forceCheckoutSucceeded = checkoutOrNotify(allConflictingRepositories, myStartPointReference, myNewBranch, true);
      if (forceCheckoutSucceeded) {
        markSuccessful(ArrayUtil.toObjectArray(allConflictingRepositories, BzrRepository.class));
        refresh(ArrayUtil.toObjectArray(allConflictingRepositories, BzrRepository.class));
      }
      return forceCheckoutSucceeded;
    }
    else {
      fatalLocalChangesError(myStartPointReference);
      return false;
    }
  }

  @NotNull
  @Override
  protected String getRollbackProposal() {
    return "However checkout has succeeded for the following " + repositories() + ":<br/>" +
           successfulRepositoriesJoined() +
           "<br/>" + String.format(ROLLBACK_PROPOSAL_FORMAT, myCurrentBranchOrRev);
  }

  @NotNull
  @Override
  protected String getOperationName() {
    return "checkout";
  }

  @Override
  protected void rollback() {
    BzrCompoundResult checkoutResult = new BzrCompoundResult(myProject);
    BzrCompoundResult deleteResult = new BzrCompoundResult(myProject);
    for (BzrRepository repository : getSuccessfulRepositories()) {
      BzrCommandResult result = myBzr.checkout(repository, myCurrentBranchOrRev, null, true);
      checkoutResult.append(repository, result);
      if (result.success() && myNewBranch != null) {
        /*
          force delete is needed, because we create new branch from branch other that the current one
          e.g. being on master create newBranch from feature,
          then rollback => newBranch is not fully merged to master (although it is obviously fully merged to feature).
         */
        deleteResult.append(repository, myBzr.branchDelete(repository, myNewBranch, true));
      }
      refresh(repository);
    }
    if (!checkoutResult.totalSuccess() || !deleteResult.totalSuccess()) {
      StringBuilder message = new StringBuilder();
      if (!checkoutResult.totalSuccess()) {
        message.append("Errors during checking out ").append(myCurrentBranchOrRev).append(": ");
        message.append(checkoutResult.getErrorOutputWithReposIndication());
      }
      if (!deleteResult.totalSuccess()) {
        message.append("Errors during deleting ").append(code(myNewBranch)).append(": ");
        message.append(deleteResult.getErrorOutputWithReposIndication());
      }
      BzrUIUtil
        .notify(BzrVcs.IMPORTANT_ERROR_NOTIFICATION, myProject, "Error during rollback", message.toString(), NotificationType.ERROR, null);
    }
  }

  @NotNull
  private String getCommonErrorTitle() {
    return "Couldn't checkout " + myStartPointReference;
  }

  @NotNull
  @Override
  public String getSuccessMessage() {
    if (myNewBranch == null) {
      return String.format("Checked out <b><code>%s</code></b>", myStartPointReference);
    }
    return String.format("Checked out new branch <b><code>%s</code></b> from <b><code>%s</code></b>", myNewBranch, myStartPointReference);
  }

  // stash - checkout - unstash
  private boolean smartCheckout(@NotNull final List<BzrRepository> repositories, @NotNull final String reference,
                                @Nullable final String newBranch, @NotNull ProgressIndicator indicator) {
    final AtomicBoolean result = new AtomicBoolean();
    BzrPreservingProcess preservingProcess = new BzrPreservingProcess(myProject, myFacade, myBzr,
                                                                      repositories, "checkout", reference, indicator,
                                                                      new Runnable() {
      @Override
      public void run() {
        result.set(checkoutOrNotify(repositories, reference, newBranch, false));
      }
    });
    preservingProcess.execute();
    return result.get();
  }

  /**
   * Checks out or shows an error message.
   */
  private boolean checkoutOrNotify(@NotNull List<BzrRepository> repositories,
                                   @NotNull String reference, @Nullable String newBranch, boolean force) {
    BzrCompoundResult compoundResult = new BzrCompoundResult(myProject);
    for (BzrRepository repository : repositories) {
      compoundResult.append(repository, myBzr.checkout(repository, reference, newBranch, force));
    }
    if (compoundResult.totalSuccess()) {
      return true;
    }
    notifyError("Couldn't checkout " + reference, compoundResult.getErrorOutputWithReposIndication());
    return false;
  }

  private void refresh(BzrRepository... repositories) {
    for (BzrRepository repository : repositories) {
      refreshRoot(repository);
      // repository state will be auto-updated with this VFS refresh => in general there is no need to call BzrRepository#update()
      // but to avoid problems of the asynchronous refresh, let's force update the repository info.
      repository.update();
    }
  }
}
