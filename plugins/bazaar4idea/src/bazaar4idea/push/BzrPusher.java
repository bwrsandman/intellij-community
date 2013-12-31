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

import bazaar4idea.*;
import bazaar4idea.branch.BzrBranchPair;
import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.commands.BzrLineHandlerListener;
import bazaar4idea.commands.BzrStandardProgressAnalyzer;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.config.BzrVcsSettings;
import bazaar4idea.config.UpdateMethod;
import bazaar4idea.history.BzrHistoryUtils;
import bazaar4idea.repo.BzrBranchTrackInfo;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.settings.BzrPushSettings;
import bazaar4idea.update.BzrUpdateProcess;
import bazaar4idea.update.BzrUpdateResult;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects information to push and performs the push.
 *
 * @author Kirill Likhodedov
 */
public final class BzrPusher {

  /**
   * if diff-log is not available (new branch is created, for example), we show a few recent commits made on the branch
   */
  static final int RECENT_COMMITS_NUMBER = 5;
  
  @Deprecated
  static final BzrRemoteBranch NO_TARGET_BRANCH = new BzrStandardRemoteBranch(BzrRemote.DOT, "", BzrBranch.DUMMY_HASH);

  private static final Logger LOG = Logger.getInstance(BzrPusher.class);
  private static final String INDICATOR_TEXT = "Pushing";
  private static final int MAX_PUSH_ATTEMPTS = 10;

  @NotNull private final Project myProject;
  @NotNull private final BzrRepositoryManager myRepositoryManager;
  @NotNull private final ProgressIndicator myProgressIndicator;
  @NotNull private final Collection<BzrRepository> myRepositories;
  @NotNull private final BzrVcsSettings mySettings;
  @NotNull private final BzrPushSettings myPushSettings;
  @NotNull private final Bzr myBzr;
  @NotNull private final BzrPlatformFacade myPlatformFacade;

  public static void showPushDialogAndPerformPush(@NotNull Project project, @NotNull BzrPlatformFacade facade) {
    final BzrPushDialog dialog = new BzrPushDialog(project);
    dialog.show();
    if (dialog.isOK()) {
      runPushInBackground(project, facade, dialog);
    }
  }

  private static void runPushInBackground(@NotNull final Project project, @NotNull final BzrPlatformFacade facade,
                                          @NotNull final BzrPushDialog dialog) {
    Task.Backgroundable task = new Task.Backgroundable(project, INDICATOR_TEXT, false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        new BzrPusher(project, facade, indicator).push(dialog.getPushInfo());
      }
    };
    BzrVcs.runInBackground(task);
  }

  // holds settings chosen in BzrRejectedPushUpdate dialog to reuse if the next push is rejected again.
  static class UpdateSettings {
    private final boolean myUpdateAllRoots;
    private final UpdateMethod myUpdateMethod;

    private UpdateSettings(boolean updateAllRoots, UpdateMethod updateMethod) {
      myUpdateAllRoots = updateAllRoots;
      myUpdateMethod = updateMethod;
    }

    public boolean shouldUpdateAllRoots() {
      return myUpdateAllRoots;
    }

    public UpdateMethod getUpdateMethod() {
      return myUpdateMethod;
    }

    public boolean shouldUpdate() {
      return getUpdateMethod() != null;
    }

    @Override
    public String toString() {
      return String.format("UpdateSettings{myUpdateAllRoots=%s, myUpdateMethod=%s}", myUpdateAllRoots, myUpdateMethod);
    }
  }

  public BzrPusher(@NotNull Project project, @NotNull BzrPlatformFacade facade, @NotNull ProgressIndicator indicator) {
    myProject = project;
    myPlatformFacade = facade;
    myProgressIndicator = indicator;
    myRepositoryManager = BzrUtil.getRepositoryManager(myProject);
    myRepositories = myRepositoryManager.getRepositories();
    mySettings = BzrVcsSettings.getInstance(myProject);
    myPushSettings = BzrPushSettings.getInstance(myProject);
    myBzr = ServiceManager.getService(Bzr.class);
  }

  /**
   * @param pushSpecs which branches in which repositories should be pushed.
   *                               The most common situation is all repositories in the project with a single currently active branch for
   *                               each of them.
   * @throws VcsException if couldn't query 'git log' about commits to be pushed.
   * @return
   */
  @NotNull
  BzrCommitsByRepoAndBranch collectCommitsToPush(@NotNull Map<BzrRepository, BzrPushSpec> pushSpecs) throws VcsException {
    Map<BzrRepository, List<BzrBranchPair>> reposAndBranchesToPush = prepareRepositoriesAndBranchesToPush(pushSpecs);
    
    Map<BzrRepository, BzrCommitsByBranch> commitsByRepoAndBranch = new HashMap<BzrRepository, BzrCommitsByBranch>();
    for (BzrRepository repository : myRepositories) {
      List<BzrBranchPair> branchPairs = reposAndBranchesToPush.get(repository);
      if (branchPairs == null) {
        continue;
      }
      BzrCommitsByBranch commitsByBranch = collectsCommitsToPush(repository, branchPairs);
      commitsByRepoAndBranch.put(repository, commitsByBranch);
    }
    return new BzrCommitsByRepoAndBranch(commitsByRepoAndBranch);
  }

  @NotNull
  private Map<BzrRepository, List<BzrBranchPair>> prepareRepositoriesAndBranchesToPush(@NotNull Map<BzrRepository, BzrPushSpec> pushSpecs) throws VcsException {
    Map<BzrRepository, List<BzrBranchPair>> res = new HashMap<BzrRepository, List<BzrBranchPair>>();
    for (BzrRepository repository : myRepositories) {
      BzrPushSpec pushSpec = pushSpecs.get(repository);
      if (pushSpec == null) {
        continue;
      }
      res.put(repository, Collections.singletonList(new BzrBranchPair(pushSpec.getSource(), pushSpec.getDest())));
    }
    return res;
  }

  @NotNull
  private BzrCommitsByBranch collectsCommitsToPush(@NotNull BzrRepository repository, @NotNull List<BzrBranchPair> sourcesDestinations)
    throws VcsException {
    Map<BzrBranch, BzrPushBranchInfo> commitsByBranch = new HashMap<BzrBranch, BzrPushBranchInfo>();

    for (BzrBranchPair sourceDest : sourcesDestinations) {
      BzrLocalBranch source = sourceDest.getBranch();
      BzrRemoteBranch dest = sourceDest.getDest();
      assert dest != null : "Destination branch can't be null here for branch " + source;

      List<BzrCommit> commits;
      BzrPushBranchInfo.Type type;
      if (dest == NO_TARGET_BRANCH) {
        commits = collectRecentCommitsOnBranch(repository, source);
        type = BzrPushBranchInfo.Type.NO_TRACKED_OR_TARGET;
      }
      else if (BzrUtil.repoContainsRemoteBranch(repository, dest)) {
        commits = collectCommitsToPush(repository, source.getName(), dest.getName());
        type = BzrPushBranchInfo.Type.STANDARD;
      } 
      else {
        commits = collectRecentCommitsOnBranch(repository, source);
        type = BzrPushBranchInfo.Type.NEW_BRANCH;
      }
      commitsByBranch.put(source, new BzrPushBranchInfo(source, dest, commits, type));
    }

    return new BzrCommitsByBranch(commitsByBranch);
  }

  private List<BzrCommit> collectRecentCommitsOnBranch(BzrRepository repository, BzrBranch source) throws VcsException {
    return BzrHistoryUtils.history(myProject, repository.getRoot(), "--max-count=" + RECENT_COMMITS_NUMBER, source.getName());
  }

  @NotNull
  private List<BzrCommit> collectCommitsToPush(@NotNull BzrRepository repository, @NotNull String source, @NotNull String destination)
    throws VcsException {
    return BzrHistoryUtils.history(myProject, repository.getRoot(), destination + ".." + source);
  }

  /**
   * Makes push, shows the result in a notification. If push for current branch is rejected, shows a dialog proposing to update.
   */
  public void push(@NotNull BzrPushInfo pushInfo) {
    push(pushInfo, null, null, 0);
  }

  /**
   * Makes push, shows the result in a notification. If push for current branch is rejected, shows a dialog proposing to update.
   * If {@code previousResult} and {@code updateSettings} are set, it means that this push is not the first, but is after a successful update.
   * In that case, if push is rejected again, the dialog is not shown, and update is performed automatically with the previously chosen
   * option.
   * Also, at the end results are merged and are shown in a single notification.
   */
  private void push(@NotNull BzrPushInfo pushInfo, @Nullable BzrPushResult previousResult, @Nullable UpdateSettings updateSettings, int attempt) {
    BzrPushResult result = tryPushAndGetResult(pushInfo);
    handleResult(pushInfo, result, previousResult, updateSettings, attempt);
  }

  @NotNull
  private BzrPushResult tryPushAndGetResult(@NotNull BzrPushInfo pushInfo) {
    BzrPushResult pushResult = new BzrPushResult(myProject);
    
    BzrCommitsByRepoAndBranch commits = pushInfo.getCommits();
    for (BzrRepository repository : commits.getRepositories()) {
      if (commits.get(repository).getAllCommits().size() == 0) {  // don't push repositories where there is nothing to push. Note that when a branch is created, several recent commits are stored in the pushInfo.
        continue;
      }
      BzrPushRepoResult repoResult = pushRepository(pushInfo, commits, repository);
      if (repoResult.getType() == BzrPushRepoResult.Type.NOT_PUSHING) {
        continue;
      }
      pushResult.append(repository, repoResult);
      BzrPushRepoResult.Type resultType = repoResult.getType();
      if (resultType == BzrPushRepoResult.Type.CANCEL || resultType == BzrPushRepoResult.Type.NOT_AUTHORIZED) { // don't proceed if user has cancelled or couldn't login
        break;
      }
    }
    myRepositoryManager.updateAllRepositories(); // new remote branch may be created
    return pushResult;
  }

  @NotNull
  private BzrPushRepoResult pushRepository(@NotNull BzrPushInfo pushInfo,
                                           @NotNull BzrCommitsByRepoAndBranch commits,
                                           @NotNull BzrRepository repository) {
    BzrPushSpec pushSpec = pushInfo.getPushSpecs().get(repository);
    BzrSimplePushResult simplePushResult = pushAndGetSimpleResult(repository, pushSpec, commits.get(repository));
    String output = simplePushResult.getOutput();
    switch (simplePushResult.getType()) {
      case SUCCESS:
        return successOrErrorRepoResult(commits, repository, output, true);
      case ERROR:
        return successOrErrorRepoResult(commits, repository, output, false);
      case REJECT:
        return getResultFromRejectedPush(commits, repository, simplePushResult);
      case NOT_AUTHORIZED:
        return BzrPushRepoResult.notAuthorized(output);
      case CANCEL:
        return BzrPushRepoResult.cancelled(output);
      case NOT_PUSHED:
        return BzrPushRepoResult.notPushed();
      default:
        return BzrPushRepoResult.cancelled(output);
    }
  }

  @NotNull
  private static BzrPushRepoResult getResultFromRejectedPush(@NotNull BzrCommitsByRepoAndBranch commits,
                                                             @NotNull BzrRepository repository,
                                                             @NotNull BzrSimplePushResult simplePushResult) {
    Collection<String> rejectedBranches = simplePushResult.getRejectedBranches();

    Map<BzrBranch, BzrPushBranchResult> resultMap = new HashMap<BzrBranch, BzrPushBranchResult>();
    BzrCommitsByBranch commitsByBranch = commits.get(repository);
    boolean pushedBranchWasRejected = false;
    for (BzrBranch branch : commitsByBranch.getBranches()) {
      BzrPushBranchResult branchResult;
      if (branchInRejected(branch, rejectedBranches)) {
        branchResult = BzrPushBranchResult.rejected();
        pushedBranchWasRejected = true;
      }
      else {
        branchResult = successfulResultForBranch(commitsByBranch, branch);
      }
      resultMap.put(branch, branchResult);
    }

    if (pushedBranchWasRejected) {
      return BzrPushRepoResult.someRejected(resultMap, simplePushResult.getOutput());
    } else {
      // The rejectedDetector detected rejected push of the branch which had nothing to push (but is behind the upstream). We are not counting it.
      return BzrPushRepoResult.success(resultMap, simplePushResult.getOutput());
    }
  }

  @NotNull
  private BzrSimplePushResult pushAndGetSimpleResult(@NotNull BzrRepository repository,
                                                            @NotNull BzrPushSpec pushSpec, @NotNull BzrCommitsByBranch commitsByBranch) {
    if (pushSpec.getDest() == NO_TARGET_BRANCH) {
      return BzrSimplePushResult.notPushed();
    }

    BzrRemote remote = pushSpec.getRemote();
    Collection<String> pushUrls = remote.getPushUrls();
    if (pushUrls.isEmpty()) {
      LOG.error("No urls or pushUrls are defined for " + remote);
      return BzrSimplePushResult.error("There are no URLs defined for remote " + remote.getName());
    }
    String url = pushUrls.iterator().next();
    BzrSimplePushResult pushResult;
    pushResult = pushNatively(repository, pushSpec, url);

    if (pushResult.getType() == BzrSimplePushResult.Type.SUCCESS) {
      setUpstream(repository, pushSpec.getSource(), pushSpec.getRemote(),  pushSpec.getDest());
    }
    
    return pushResult;
  }

  private static void setUpstream(@NotNull BzrRepository repository,
                                  @NotNull BzrLocalBranch source, @NotNull BzrRemote remote, @NotNull BzrRemoteBranch dest) {
    if (!branchTrackingInfoIsSet(repository, source)) {
      Project project = repository.getProject();
      VirtualFile root = repository.getRoot();
      String branchName = source.getName();
      try {
        boolean rebase = getMergeOrRebaseConfig(project, root);
        BzrConfigUtil.setValue(project, root, "branch." + branchName + ".remote", remote.getName());
        BzrConfigUtil
          .setValue(project, root, "branch." + branchName + ".merge", BzrBranch.REFS_HEADS_PREFIX + dest.getNameForRemoteOperations());
        if (rebase) {
          BzrConfigUtil.setValue(project, root, "branch." + branchName + ".rebase", "true");
        }
      }
      catch (VcsException e) {
        LOG.error(String.format("Couldn't set up tracking for source branch %s, target branch %s, remote %s in root %s",
                                source, dest, remote, repository), e);
        Notificator.getInstance(project).notify(BzrVcs.NOTIFICATION_GROUP_ID, "", "Couldn't set up branch tracking",
                                                        NotificationType.ERROR);
      }
    }
  }

  private static boolean getMergeOrRebaseConfig(Project project, VirtualFile root) throws VcsException {
    String autoSetupRebase = BzrConfigUtil.getValue(project, root, BzrConfigUtil.BRANCH_AUTOSETUP_REBASE);
    return autoSetupRebase != null && (autoSetupRebase.equals("remote") || autoSetupRebase.equals("always"));
  }

  private static boolean branchTrackingInfoIsSet(@NotNull BzrRepository repository, @NotNull BzrLocalBranch source) {
    for (BzrBranchTrackInfo trackInfo : repository.getBranchTrackInfos()) {
      if (trackInfo.getLocalBranch().equals(source)) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  private static String formPushSpec(@NotNull BzrPushSpec spec, @NotNull BzrRemote remote) {
    String destWithRemote = spec.getDest().getName();
    String prefix = remote.getName() + "/";
    String destName;
    if (destWithRemote.startsWith(prefix)) {
      destName = destWithRemote.substring(prefix.length());
    }
    else {
      LOG.error("Destination remote branch has invalid name. Remote branch name: " + destWithRemote + "\nRemote: " + remote);
      destName = destWithRemote;
    }
    return spec.getSource().getName() + ":" + destName;
  }

  @NotNull
  private BzrSimplePushResult pushNatively(BzrRepository repository, BzrPushSpec pushSpec, @NotNull String url) {
    BzrPushRejectedDetector rejectedDetector = new BzrPushRejectedDetector();
    BzrLineHandlerListener progressListener = BzrStandardProgressAnalyzer.createListener(myProgressIndicator);
    BzrCommandResult res = myBzr.push(repository, pushSpec, url, rejectedDetector, progressListener);
    if (rejectedDetector.rejected()) {
      Collection<String> rejectedBranches = rejectedDetector.getRejectedBranches();
      return BzrSimplePushResult.reject(rejectedBranches);
    }
    else if (res.success()) {
      return BzrSimplePushResult.success();
    }
    else {
      return BzrSimplePushResult.error(res.getErrorOutputAsHtmlString());
    }
  }

  @NotNull
  private static BzrPushRepoResult successOrErrorRepoResult(@NotNull BzrCommitsByRepoAndBranch commits, @NotNull BzrRepository repository, @NotNull String output, boolean success) {
    BzrPushRepoResult repoResult;
    Map<BzrBranch, BzrPushBranchResult> resultMap = new HashMap<BzrBranch, BzrPushBranchResult>();
    BzrCommitsByBranch commitsByBranch = commits.get(repository);
    for (BzrBranch branch : commitsByBranch.getBranches()) {
      BzrPushBranchResult branchResult = success ?
                                         successfulResultForBranch(commitsByBranch, branch) :
                                         BzrPushBranchResult.error();
      resultMap.put(branch, branchResult);
    }
    repoResult = success ? BzrPushRepoResult.success(resultMap, output) : BzrPushRepoResult.error(resultMap, output);
    return repoResult;
  }

  @NotNull
  private static BzrPushBranchResult successfulResultForBranch(@NotNull BzrCommitsByBranch commitsByBranch, @NotNull BzrBranch branch) {
    BzrPushBranchInfo branchInfo = commitsByBranch.get(branch);
    if (branchInfo.isNewBranchCreated()) {
      return BzrPushBranchResult.newBranch(branchInfo.getDestBranch().getName());
    } 
    return BzrPushBranchResult.success(branchInfo.getCommits().size());
  }

  private static boolean branchInRejected(@NotNull BzrBranch branch, @NotNull Collection<String> rejectedBranches) {
    String branchName = branch.getName();
    final String REFS_HEADS = "refs/heads/";
    if (branchName.startsWith(REFS_HEADS)) {
      branchName = branchName.substring(REFS_HEADS.length());
    }
    
    for (String rejectedBranch : rejectedBranches) {
      if (rejectedBranch.equals(branchName) || (rejectedBranch.startsWith(REFS_HEADS) &&  rejectedBranch.substring(REFS_HEADS.length()).equals(branchName))) {
        return true;
      }
    }
    return false;
  }

  // if all repos succeeded, show notification.
  // if all repos failed, show notification.
  // if some repos failed, show notification with both results.
  // if in failed repos, some branches were rejected, it is a warning-type, but not an error.
  // if in a failed repo, current branch was rejected, propose to update the branch. Don't do it if not current branch was rejected,
  // since it is difficult to update such a branch.
  // if in a failed repo, a branch was rejected that had nothing to push, don't notify about the rejection.
  // Besides all of the above, don't confuse users with 1 repository with all this "repository/root" stuff;
  // don't confuse users which push only a single branch with all this "branch" stuff.
  private void handleResult(@NotNull BzrPushInfo pushInfo, @NotNull BzrPushResult result, @Nullable BzrPushResult previousResult, @Nullable UpdateSettings updateSettings,
                            int pushAttempt) {
    result.mergeFrom(previousResult);

    if (result.isEmpty()) {
      BzrVcs.NOTIFICATION_GROUP_ID.createNotification("Nothing to push", NotificationType.INFORMATION).notify(myProject);
    }
    else if (result.wasErrorCancelOrNotAuthorized()) {
      // if there was an error on any repo, we won't propose to update even if current branch of a repo was rejected
      result.createNotification().notify(myProject);
    }
    else {
      // there were no errors, but there might be some rejected branches on some of the repositories
      // => for current branch propose to update and re-push it. For others just warn
      Map<BzrRepository, BzrBranch> rejectedPushesForCurrentBranch = result.getRejectedPushesFromCurrentBranchToTrackedBranch(pushInfo);

      if (pushAttempt <= MAX_PUSH_ATTEMPTS && !rejectedPushesForCurrentBranch.isEmpty()) {

        LOG.info(
          String.format("Rejected pushes for current branches: %n%s%nUpdate settings: %s", rejectedPushesForCurrentBranch, updateSettings));
        
        if (updateSettings == null) {
          // show dialog only when push is rejected for the first time in a row, otherwise reuse previously chosen update method
          // and don't show the dialog again if user has chosen not to ask again
          updateSettings = readUpdateSettings();
          if (!mySettings.autoUpdateIfPushRejected()) {
            final BzrRejectedPushUpdateDialog
              dialog = new BzrRejectedPushUpdateDialog(myProject, rejectedPushesForCurrentBranch.keySet(), updateSettings);
            final int exitCode = showDialogAndGetExitCode(dialog);
            updateSettings = new UpdateSettings(dialog.shouldUpdateAll(), getUpdateMethodFromDialogExitCode(exitCode));
            saveUpdateSettings(updateSettings);
          }
        } 

        if (updateSettings.shouldUpdate()) {
          Collection<BzrRepository> repositoriesToUpdate = getRootsToUpdate(rejectedPushesForCurrentBranch, updateSettings.shouldUpdateAllRoots());
          BzrPushResult adjustedPushResult = result.remove(rejectedPushesForCurrentBranch);
          adjustedPushResult.markUpdateStartIfNotMarked(repositoriesToUpdate);
          boolean updateResult = update(repositoriesToUpdate, updateSettings.getUpdateMethod());
          if (updateResult) {
            myProgressIndicator.setText(INDICATOR_TEXT);
            BzrPushInfo newPushInfo = pushInfo.retain(rejectedPushesForCurrentBranch);
            push(newPushInfo, adjustedPushResult, updateSettings, pushAttempt + 1);
            return; // don't notify - next push will notify all results in compound
          }
        }

      }

      result.createNotification().notify(myProject);
    }
  }

  private void saveUpdateSettings(@NotNull UpdateSettings updateSettings) {
    UpdateMethod updateMethod = updateSettings.getUpdateMethod();
    if (updateMethod != null) { // null if user has pressed cancel
      myPushSettings.setUpdateAllRoots(updateSettings.shouldUpdateAllRoots());
      myPushSettings.setUpdateMethod(updateMethod);
    }
  }

  @NotNull
  private UpdateSettings readUpdateSettings() {
    boolean updateAllRoots = myPushSettings.shouldUpdateAllRoots();
    UpdateMethod updateMethod = myPushSettings.getUpdateMethod();
    return new UpdateSettings(updateAllRoots, updateMethod);
  }

  private int showDialogAndGetExitCode(@NotNull final BzrRejectedPushUpdateDialog dialog) {
    final AtomicInteger exitCode = new AtomicInteger();
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        dialog.show();
        exitCode.set(dialog.getExitCode());
      }
    });
    int code = exitCode.get();
    if (code != DialogWrapper.CANCEL_EXIT_CODE) {
      mySettings.setAutoUpdateIfPushRejected(dialog.shouldAutoUpdateInFuture());
    }
    return code;
  }

  /**
   * @return update method selected in the dialog or {@code null} if user pressed Cancel, i.e. doesn't want to update.
   */
  @Nullable
  private static UpdateMethod getUpdateMethodFromDialogExitCode(int exitCode) {
    switch (exitCode) {
      case BzrRejectedPushUpdateDialog.MERGE_EXIT_CODE:  return UpdateMethod.MERGE;
      case BzrRejectedPushUpdateDialog.REBASE_EXIT_CODE: return UpdateMethod.REBASE;
    }
    return null;
  }

  @NotNull
  private Collection<BzrRepository> getRootsToUpdate(@NotNull Map<BzrRepository, BzrBranch> rejectedPushesForCurrentBranch, boolean updateAllRoots) {
    return updateAllRoots ? myRepositories : rejectedPushesForCurrentBranch.keySet();
  }
  
  private boolean update(@NotNull Collection<BzrRepository> rootsToUpdate, @NotNull UpdateMethod updateMethod) {
    BzrUpdateProcess.UpdateMethod um = updateMethod == UpdateMethod.MERGE ? BzrUpdateProcess.UpdateMethod.MERGE : BzrUpdateProcess.UpdateMethod.REBASE;
    BzrUpdateResult updateResult = new BzrUpdateProcess(myProject, myPlatformFacade, myProgressIndicator,
                                                        new HashSet<BzrRepository>(rootsToUpdate), UpdatedFiles.create()).update(um);
    for (BzrRepository repository : rootsToUpdate) {
      repository.getRoot().refresh(true, true);
    }
    if (updateResult == BzrUpdateResult.SUCCESS) {
      return true;
    }
    else if (updateResult == BzrUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS || updateResult == BzrUpdateResult.INCOMPLETE) {
      String title = "Push cancelled";
      String description;
      if (updateResult == BzrUpdateResult.INCOMPLETE) {
        description = "Push has been cancelled, because not all conflicts were resolved during update.<br/>" +
                      "Resolve the conflicts and invoke push again.";
      }
      else {
        description = "Push has been cancelled, because there were conflicts during update.<br/>" +
                      "Check that conflicts were resolved correctly, and invoke push again.";
      }
      new Notification(BzrVcs.MINOR_NOTIFICATION.getDisplayId(), title, description, NotificationType.WARNING).notify(myProject);
      return false;
    }
    else {
      return false;
    }
  }

}
