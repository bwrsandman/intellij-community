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
import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.merge.BzrMergeCommittingConflictResolver;
import bazaar4idea.merge.BzrMerger;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.util.BzrPreservingProcess;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;
import bazaar4idea.Notificator;
import bazaar4idea.commands.*;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.HyperlinkEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Kirill Likhodedov
 */
class BzrMergeOperation extends BzrBranchOperation {

  private static final Logger LOG = Logger.getInstance(BzrMergeOperation.class);
  public static final String ROLLBACK_PROPOSAL = "You may rollback (reset to the commit before merging) not to let branches diverge.";

  @NotNull private final ChangeListManager myChangeListManager;
  @NotNull private final String myBranchToMerge;
  private final BzrBrancher.DeleteOnMergeOption myDeleteOnMerge;
  @NotNull private final Map<BzrRepository, String> myCurrentRevisionsBeforeMerge;

  // true in value, if we've stashed local changes before merge and will need to unstash after resolving conflicts.
  @NotNull private final Map<BzrRepository, Boolean> myConflictedRepositories = new HashMap<BzrRepository, Boolean>();
  private BzrPreservingProcess myPreservingProcess;

  BzrMergeOperation(@NotNull Project project,
                    BzrPlatformFacade facade,
                    @NotNull Bzr bzr,
                    @NotNull BzrBranchUiHandler uiHandler,
                    @NotNull Collection<BzrRepository> repositories,
                    @NotNull String branchToMerge,
                    BzrBrancher.DeleteOnMergeOption deleteOnMerge,
                    @NotNull Map<BzrRepository, String> currentRevisionsBeforeMerge) {
    super(project, facade, bzr, uiHandler, repositories);
    myBranchToMerge = branchToMerge;
    myDeleteOnMerge = deleteOnMerge;
    myCurrentRevisionsBeforeMerge = currentRevisionsBeforeMerge;
    myChangeListManager = myFacade.getChangeListManager(myProject);
  }

  @Override
  protected void execute() {
    LOG.info("starting");
    saveAllDocuments();
    boolean fatalErrorHappened = false;
    int alreadyUpToDateRepositories = 0;
    while (hasMoreRepositories() && !fatalErrorHappened) {
      final BzrRepository repository = next();
      LOG.info("next repository: " + repository);

      VirtualFile root = repository.getRoot();
      BzrLocalChangesWouldBeOverwrittenDetector localChangesDetector =
        new BzrLocalChangesWouldBeOverwrittenDetector(root, BzrLocalChangesWouldBeOverwrittenDetector.Operation.MERGE);
      BzrSimpleEventDetector unmergedFiles = new BzrSimpleEventDetector(BzrSimpleEventDetector.Event.UNMERGED_PREVENTING_MERGE);
      BzrUntrackedFilesOverwrittenByOperationDetector untrackedOverwrittenByMerge =
        new BzrUntrackedFilesOverwrittenByOperationDetector(root);
      BzrSimpleEventDetector mergeConflict = new BzrSimpleEventDetector(BzrSimpleEventDetector.Event.MERGE_CONFLICT);
      BzrSimpleEventDetector alreadyUpToDateDetector = new BzrSimpleEventDetector(BzrSimpleEventDetector.Event.ALREADY_UP_TO_DATE);

      BzrCommandResult result = myBzr.merge(repository, myBranchToMerge, Collections.<String>emptyList(),
                                          localChangesDetector, unmergedFiles, untrackedOverwrittenByMerge, mergeConflict,
                                          alreadyUpToDateDetector);
      if (result.success()) {
        LOG.info("Merged successfully");
        refresh(repository);
        markSuccessful(repository);
        if (alreadyUpToDateDetector.hasHappened()) {
          alreadyUpToDateRepositories += 1;
        }
      }
      else if (unmergedFiles.hasHappened()) {
        LOG.info("Unmerged files error!");
        fatalUnmergedFilesError();
        fatalErrorHappened = true;
      }
      else if (localChangesDetector.wasMessageDetected()) {
        LOG.info("Local changes would be overwritten by merge!");
        boolean smartMergeSucceeded = proposeSmartMergePerformAndNotify(repository, localChangesDetector);
        if (!smartMergeSucceeded) {
          fatalErrorHappened = true;
        }
      }
      else if (mergeConflict.hasHappened()) {
        LOG.info("Merge conflict");
        myConflictedRepositories.put(repository, Boolean.FALSE);
        refresh(repository);
        markSuccessful(repository);
      }
      else if (untrackedOverwrittenByMerge.wasMessageDetected()) {
        LOG.info("Untracked files would be overwritten by merge!");
        fatalUntrackedFilesError(untrackedOverwrittenByMerge.getFiles());
        fatalErrorHappened = true;
      }
      else {
        LOG.info("Unknown error. " + result);
        fatalError(getCommonErrorTitle(), result.getErrorOutputAsJoinedString());
        fatalErrorHappened = true;
      }
    }

    if (fatalErrorHappened) {
      notifyAboutRemainingConflicts();
    }
    else {
      boolean allConflictsResolved = resolveConflicts();
      if (allConflictsResolved) {
        if (alreadyUpToDateRepositories < getRepositories().size()) {
          notifySuccess();
        }
        else {
          notifySuccess("Already up-to-date");
        }
      }
    }

    restoreLocalChanges();
  }

  private void notifyAboutRemainingConflicts() {
    if (!myConflictedRepositories.isEmpty()) {
      new MyMergeConflictResolver().notifyUnresolvedRemain();
    }
  }

  @Override
  protected void notifySuccess(@NotNull String message) {
    switch (myDeleteOnMerge) {
      case DELETE:
        super.notifySuccess(message);
        UIUtil.invokeLaterIfNeeded(new Runnable() { // bg process needs to be started from the EDT
          @Override
          public void run() {
            BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
            brancher.deleteBranch(myBranchToMerge, new ArrayList<BzrRepository>(getRepositories()));
          }
        });
        break;
      case PROPOSE:
        String description = message + "<br/><a href='delete'>Delete " + myBranchToMerge + "</a>";
        myUiHandler.notifySuccess("", description, new DeleteMergedLocalBranchNotificationListener());
        break;
      case NOTHING:
        super.notifySuccess(message);
        break;
    }
  }

  private boolean resolveConflicts() {
    if (!myConflictedRepositories.isEmpty()) {
      return new MyMergeConflictResolver().merge();
    }
    return true;
  }

  private boolean proposeSmartMergePerformAndNotify(@NotNull BzrRepository repository,
                                          @NotNull BzrMessageWithFilesDetector localChangesOverwrittenByMerge) {
    Pair<List<BzrRepository>, List<Change>> conflictingRepositoriesAndAffectedChanges =
      getConflictingRepositoriesAndAffectedChanges(repository, localChangesOverwrittenByMerge, myCurrentBranchOrRev, myBranchToMerge);
    List<BzrRepository> allConflictingRepositories = conflictingRepositoriesAndAffectedChanges.getFirst();
    List<Change> affectedChanges = conflictingRepositoriesAndAffectedChanges.getSecond();

    int smartCheckoutDecision = myUiHandler.showSmartOperationDialog(myProject, affectedChanges, "merge", false);
    if (smartCheckoutDecision == BzrSmartOperationDialog.SMART_EXIT_CODE) {
      return doSmartMerge(allConflictingRepositories);
    }
    else {
      fatalLocalChangesError(myBranchToMerge);
      return false;
    }
  }

  private void restoreLocalChanges() {
    if (myPreservingProcess != null) {
      myPreservingProcess.load();
    }
  }

  private boolean doSmartMerge(@NotNull final Collection<BzrRepository> repositories) {
    final AtomicBoolean success = new AtomicBoolean();
    myPreservingProcess = new BzrPreservingProcess(myProject, myFacade, myBzr, repositories, "merge", myBranchToMerge, getIndicator(),
      new Runnable() {
        @Override
        public void run() {
          success.set(doMerge(repositories));
        }
      });
    myPreservingProcess.execute(new Computable<Boolean>() {
      @Override
      public Boolean compute() {
        return myConflictedRepositories.isEmpty();
      }
    });
    return success.get();
  }

  /**
   * Performs merge in the given repositories.
   * Handle only merge conflict situation: all other cases should have been handled before and are treated as errors.
   * Conflict is treated as a success: the repository with conflict is remembered and will be handled later along with all other conflicts.
   * If an error happens in one repository, the method doesn't go further in others, and shows a notification.
   *
   * @return true if merge has succeeded without errors (but possibly with conflicts) in all repositories;
   *         false if it failed at least in one of them.
   */
  private boolean doMerge(@NotNull Collection<BzrRepository> repositories) {
    for (BzrRepository repository : repositories) {
      BzrSimpleEventDetector mergeConflict = new BzrSimpleEventDetector(BzrSimpleEventDetector.Event.MERGE_CONFLICT);
      BzrCommandResult result = myBzr.merge(repository, myBranchToMerge, Collections.<String>emptyList(), mergeConflict);
      if (!result.success()) {
        if (mergeConflict.hasHappened()) {
          myConflictedRepositories.put(repository, Boolean.TRUE);
          refresh(repository);
          markSuccessful(repository);
        }
        else {
          fatalError(getCommonErrorTitle(), result.getErrorOutputAsJoinedString());
          return false;
        }
      }
      else {
        refresh(repository);
        markSuccessful(repository);
      }
    }
    return true;
  }

  @NotNull
  private String getCommonErrorTitle() {
    return "Couldn't merge " + myBranchToMerge;
  }

  @Override
  protected void rollback() {
    LOG.info("starting rollback...");
    Collection<BzrRepository> repositoriesForSmartRollback = new ArrayList<BzrRepository>();
    Collection<BzrRepository> repositoriesForSimpleRollback = new ArrayList<BzrRepository>();
    Collection<BzrRepository> repositoriesForMergeRollback = new ArrayList<BzrRepository>();
    for (BzrRepository repository : getSuccessfulRepositories()) {
      if (myConflictedRepositories.containsKey(repository)) {
        repositoriesForMergeRollback.add(repository);
      }
      else if (thereAreLocalChangesIn(repository)) {
        repositoriesForSmartRollback.add(repository);
      }
      else {
        repositoriesForSimpleRollback.add(repository);
      }
    }

    LOG.info("for smart rollback: " + DvcsUtil.getShortNames(repositoriesForSmartRollback) +
             "; for simple rollback: " + DvcsUtil.getShortNames(repositoriesForSimpleRollback) +
             "; for merge rollback: " + DvcsUtil.getShortNames(repositoriesForMergeRollback));

    BzrCompoundResult result = smartRollback(repositoriesForSmartRollback);
    for (BzrRepository repository : repositoriesForSimpleRollback) {
      result.append(repository, rollback(repository));
    }
    for (BzrRepository repository : repositoriesForMergeRollback) {
      result.append(repository, rollbackMerge(repository));
    }
    myConflictedRepositories.clear();

    if (!result.totalSuccess()) {
      Notificator.getInstance(myProject).notifyError("Error during rollback", result.getErrorOutputWithReposIndication());
    }
    LOG.info("rollback finished.");
  }

  @NotNull
  private BzrCompoundResult smartRollback(@NotNull final Collection<BzrRepository> repositories) {
    LOG.info("Starting smart rollback...");
    final BzrCompoundResult result = new BzrCompoundResult(myProject);
    BzrPreservingProcess preservingProcess = new BzrPreservingProcess(myProject, myFacade, myBzr, repositories, "merge", myBranchToMerge,
                                                                      getIndicator(),
      new Runnable() {
        @Override public void run() {
          for (BzrRepository repository : repositories) {
            result.append(repository, rollback(repository));
          }
        }
      });
    preservingProcess.execute();
    LOG.info("Smart rollback completed.");
    return result;
  }

  @NotNull
  private BzrCommandResult rollback(@NotNull BzrRepository repository) {
    return myBzr.resetHard(repository, myCurrentRevisionsBeforeMerge.get(repository));
  }

  @NotNull
  private BzrCommandResult rollbackMerge(@NotNull BzrRepository repository) {
    BzrCommandResult result = myBzr.resetMerge(repository, null);
    refresh(repository);
    return result;
  }

  private boolean thereAreLocalChangesIn(@NotNull BzrRepository repository) {
    return !myChangeListManager.getChangesIn(repository.getRoot()).isEmpty();
  }

  @NotNull
  @Override
  public String getSuccessMessage() {
    return String.format("Merged <b><code>%s</code></b> to <b><code>%s</code></b>", myBranchToMerge, myCurrentBranchOrRev);
  }

  @NotNull
  @Override
  protected String getRollbackProposal() {
    return "However merge has succeeded for the following " + repositories() + ":<br/>" +
           successfulRepositoriesJoined() +
           "<br/>" + ROLLBACK_PROPOSAL;
  }

  @NotNull
  @Override
  protected String getOperationName() {
    return "merge";
  }

  private void refresh(BzrRepository... repositories) {
    for (BzrRepository repository : repositories) {
      refreshRoot(repository);
      repository.update();
    }
  }

  private class MyMergeConflictResolver extends BzrMergeCommittingConflictResolver {
    public MyMergeConflictResolver() {
      super(BzrMergeOperation.this.myProject, myBzr, new BzrMerger(BzrMergeOperation.this.myProject),
            BzrUtil.getRootsFromRepositories(BzrMergeOperation.this.myConflictedRepositories.keySet()), new Params(), true);
    }

    @Override
    protected void notifyUnresolvedRemain() {
      Notificator.getInstance(myProject).notify(
        BzrVcs.IMPORTANT_ERROR_NOTIFICATION, "Merged branch " + myBranchToMerge + " with conflicts",
        "Unresolved conflicts remain in the project. <a href='resolve'>Resolve now.</a>", NotificationType.WARNING,
        getResolveLinkListener());
    }
  }

  private class DeleteMergedLocalBranchNotificationListener implements NotificationListener {
    @Override
    public void hyperlinkUpdate(@NotNull Notification notification,
                                @NotNull HyperlinkEvent event) {
      if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getDescription().equalsIgnoreCase("delete")) {
        BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
        brancher.deleteBranch(myBranchToMerge, new ArrayList<BzrRepository>(getRepositories()));
      }
    }
  }
}
