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
import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.merge.MergeChangeCollector;
import bazaar4idea.repo.BzrRepository;
import com.intellij.history.Label;
import com.intellij.history.LocalHistory;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.ex.ProjectLevelVcsManagerEx;
import com.intellij.openapi.vcs.update.ActionInfo;
import com.intellij.openapi.vcs.update.UpdateInfoTree;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
* @author Kirill Likhodedov
*/
class BzrPushResult {

  private static final Logger LOG = Logger.getInstance(BzrPushResult.class);

  private final Project myProject;
  private final Map<BzrRepository, BzrPushRepoResult> myResults = new HashMap<BzrRepository, BzrPushRepoResult>();
  private final Map<BzrRepository, BzrRevisionNumber> myUpdateStarts = new HashMap<BzrRepository, BzrRevisionNumber>();
  private Label myBeforeUpdateLabel;

  BzrPushResult(@NotNull Project project) {
    this(project, null, new HashMap<BzrRepository, BzrRevisionNumber>());
  }
  
  private BzrPushResult(@NotNull Project project,
                        @Nullable Label beforeUpdateLabel,
                        @NotNull Map<BzrRepository, BzrRevisionNumber> updateStarts) {
    myProject = project;
    myBeforeUpdateLabel = beforeUpdateLabel;
    for (Map.Entry<BzrRepository, BzrRevisionNumber> entry : updateStarts.entrySet()) {
      myUpdateStarts.put(entry.getKey(), entry.getValue());
    }
  }

  void append(@NotNull BzrRepository repository, @NotNull BzrPushRepoResult result) {
    myResults.put(repository, result);
  }

  /**
   * For the specified repositories remembers the revision when update process (actually, auto-update inside the push process) has started.
   */
  void markUpdateStartIfNotMarked(@NotNull Collection<BzrRepository> repositories) {
    if (myUpdateStarts.isEmpty()) {
      myBeforeUpdateLabel = LocalHistory.getInstance().putSystemLabel(myProject, "Before push");
    }
    for (BzrRepository repository : repositories) {
      if (!myUpdateStarts.containsKey(repository)) {
        String currentRevision = repository.getCurrentRevision();
        if (currentRevision != null) {
          myUpdateStarts.put(repository, new BzrRevisionNumber(currentRevision));
        }
      }
    }
  }

  boolean wasErrorCancelOrNotAuthorized() {
    for (BzrPushRepoResult repoResult : myResults.values()) {
      if (repoResult.isOneOfErrors()) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  private GroupedResult group() {
    final Map<BzrRepository, BzrPushRepoResult> successfulResults = new HashMap<BzrRepository, BzrPushRepoResult>();
    final Map<BzrRepository, BzrPushRepoResult> errorResults = new HashMap<BzrRepository, BzrPushRepoResult>();
    final Map<BzrRepository, BzrPushRepoResult> rejectedResults = new HashMap<BzrRepository, BzrPushRepoResult>();

    for (Map.Entry<BzrRepository, BzrPushRepoResult> entry : myResults.entrySet()) {
      BzrRepository repository = entry.getKey();
      BzrPushRepoResult repoResult = entry.getValue();
      switch (repoResult.getType()) {
        case SUCCESS:
          successfulResults.put(repository, repoResult);
          break;
        case ERROR:
        case CANCEL:
        case NOT_AUTHORIZED:
          errorResults.put(repository, repoResult);
          break;
        case NOT_PUSHING:
          break;
        case SOME_REJECTED:
          rejectedResults.put(repository, repoResult);
          break;
      }
    }
    return new GroupedResult(successfulResults, errorResults, rejectedResults);
  }

  boolean isEmpty() {
    return myResults.isEmpty();
  }

  @NotNull
  BzrPushResult remove(@NotNull Map<BzrRepository, BzrBranch> repoBranchPairs) {
    BzrPushResult result = new BzrPushResult(myProject, myBeforeUpdateLabel, myUpdateStarts);
    for (Map.Entry<BzrRepository, BzrPushRepoResult> entry : myResults.entrySet()) {
      BzrRepository repository = entry.getKey();
      BzrPushRepoResult repoResult = entry.getValue();
      if (repoBranchPairs.containsKey(repository)) {
        BzrPushRepoResult adjustedResult = repoResult.remove(repoBranchPairs.get(repository));
        if (!repoResult.isEmpty()) {
          result.append(repository, adjustedResult);
        }
      } else {
        result.append(repository, repoResult);
      }
    }
    return result;
  }

  /**
   * Merges the given results to this result.
   * In the case of conflict (i.e. different results for a repository-branch pair), current result is preferred over the previous one.
   */
  void mergeFrom(@Nullable BzrPushResult previousResult) {
    if (previousResult == null) {
      return;
    }

    for (Map.Entry<BzrRepository, BzrPushRepoResult> entry : previousResult.myResults.entrySet()) {
      BzrRepository repository = entry.getKey();
      BzrPushRepoResult repoResult = entry.getValue();
      if (myResults.containsKey(repository)) {
        myResults.get(repository).mergeFrom(previousResult.myResults.get(repository));
      } else {
        append(repository, repoResult);
      }
    }

    for (Map.Entry<BzrRepository, BzrRevisionNumber> entry : previousResult.myUpdateStarts.entrySet()) {
      myUpdateStarts.put(entry.getKey(), entry.getValue());
    }
    myBeforeUpdateLabel = previousResult.myBeforeUpdateLabel;
  }

  @NotNull
  Map<BzrRepository, BzrBranch> getRejectedPushesFromCurrentBranchToTrackedBranch(BzrPushInfo pushInfo) {
    final Map<BzrRepository, BzrBranch> rejectedPushesForCurrentBranch = new HashMap<BzrRepository, BzrBranch>();
    for (Map.Entry<BzrRepository, BzrPushRepoResult> entry : group().myRejectedResults.entrySet()) {
      BzrRepository repository = entry.getKey();
      BzrBranch currentBranch = repository.getCurrentBranch();
      if (currentBranch == null) {
        continue;
      }
      BzrPushRepoResult repoResult = entry.getValue();
      BzrPushBranchResult curBranchResult = repoResult.getBranchResults().get(currentBranch);

      if (curBranchResult == null) {
        continue;
      }

      String trackedBranchName;
      try {
        String simpleName = BzrBranchUtil.getTrackedBranchName(myProject, repository.getRoot(), currentBranch.getName());
        if (simpleName == null) {
          continue;
        }
        if (simpleName.startsWith(BzrBranch.REFS_HEADS_PREFIX)) {
          simpleName = simpleName.substring(BzrBranch.REFS_HEADS_PREFIX.length());
        }
        String remote = BzrBranchUtil.getTrackedRemoteName(myProject, repository.getRoot(), currentBranch.getName());
        if (remote == null) {
          continue;
        }
        trackedBranchName = remote + "/" + simpleName;
      }
      catch (VcsException e) {
        LOG.info("Couldn't get tracked branch for branch " + currentBranch, e);
        continue;
      }
      if (!pushInfo.getPushSpecs().get(repository).getDest().getName().equals(trackedBranchName)) {
        // push from current branch was rejected, but it was a push not to the tracked branch => ignore
        continue;
      }
      if (curBranchResult.isRejected()) {
        rejectedPushesForCurrentBranch.put(repository, currentBranch);
      }
    }
    return rejectedPushesForCurrentBranch;
  }

  /**
   * Constructs the HTML-formatted message from error outputs of failed repositories.
   * If there is only 1 repository in the project, just returns the error without writing the repository url (to avoid confusion for people
   * with only 1 root ever).
   * Otherwise adds repository URL to the error that repository produced.
   * 
   * The procedure also includes collecting for updated files (if an auto-update was performed during the push), which may be lengthy.
   */
  @NotNull
  Notification createNotification() {
    final UpdatedFiles updatedFiles = collectUpdatedFiles();

    GroupedResult groupedResult = group();
    
    boolean error = !groupedResult.myErrorResults.isEmpty();
    boolean rejected = !groupedResult.myRejectedResults.isEmpty();
    boolean success = !groupedResult.mySuccessfulResults.isEmpty();

    boolean onlyError = error && !rejected && !success;
    boolean onlyRejected = rejected && !error && !success;
    final boolean onlySuccess = success && !rejected && !error;

    int pushedCommitsNumber = calcPushedCommitTotalNumber(myResults);
    
    String title;
    NotificationType notificationType;
    if (error) {
      if (onlyError) {
        title = "Push failed";
      } else {
        title = "Push partially failed";
        if (success) {
          title += ", " + commits(pushedCommitsNumber) + " pushed";
        }
      }
      notificationType = NotificationType.ERROR;
    } else if (rejected) {
      if (onlyRejected) {
        title = "Push rejected";
      } else {
        title = "Push partially rejected, " + commits(pushedCommitsNumber) + " pushed";
      }
      notificationType = NotificationType.WARNING;
    } else {
      notificationType = NotificationType.INFORMATION;
      title = "Push successful";
    }
    
    String errorReport = reportForGroup(groupedResult.myErrorResults, GroupedResult.Type.ERROR);
    String successReport = reportForGroup(groupedResult.mySuccessfulResults, GroupedResult.Type.SUCCESS);
    String rejectedReport = reportForGroup(groupedResult.myRejectedResults, GroupedResult.Type.REJECT);

    StringBuilder sb = new StringBuilder();
    sb.append(errorReport);
    sb.append(rejectedReport);
    sb.append(successReport);
    
    if (!updatedFiles.isEmpty()) {
      sb.append("<a href='UpdatedFiles'>View files updated during the push</a>");
    }

    NotificationListener viewUpdateFilesListener = new ViewUpdatedFilesNotificationListener(updatedFiles);

    if (onlySuccess) {
      return new Notification(BzrVcs.NOTIFICATION_GROUP_ID.getDisplayId(), title, sb.toString(), notificationType, viewUpdateFilesListener).setImportant(false);
    }

    return new Notification(BzrVcs.IMPORTANT_ERROR_NOTIFICATION.getDisplayId(), title, sb.toString(), notificationType, viewUpdateFilesListener);
  }

  @NotNull
  private UpdatedFiles collectUpdatedFiles() {
    UpdatedFiles updatedFiles = UpdatedFiles.create();
    for (Map.Entry<BzrRepository, BzrRevisionNumber> updatedRepository : myUpdateStarts.entrySet()) {
      BzrRepository repository = updatedRepository.getKey();
      final MergeChangeCollector collector = new MergeChangeCollector(myProject, repository.getRoot(), updatedRepository.getValue());
      final ArrayList<VcsException> exceptions = new ArrayList<VcsException>();
      collector.collect(updatedFiles, exceptions);
      for (VcsException exception : exceptions) {
        LOG.info(exception);
      }
    }
    return updatedFiles;
  }

  private static int calcPushedCommitTotalNumber(@NotNull Map<BzrRepository, BzrPushRepoResult> successfulResults) {
    int sum = 0;
    for (BzrPushRepoResult pushRepoResult : successfulResults.values()) {
      for (BzrPushBranchResult branchResult : pushRepoResult.getBranchResults().values()) {
        sum += branchResult.getNumberOfPushedCommits();
      }
    }
    return sum;
  }

  @NotNull
  private String reportForGroup(@NotNull Map<BzrRepository, BzrPushRepoResult> groupResult, @NotNull GroupedResult.Type resultType) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<BzrRepository, BzrPushRepoResult> entry : groupResult.entrySet()) {
      BzrRepository repository = entry.getKey();
      BzrPushRepoResult result = entry.getValue();

      if (!BzrUtil.justOneBzrRepository(myProject)) {
        sb.append("<code>" + repository.getPresentableUrl() + "</code>:<br/>");
      }
      if (resultType == GroupedResult.Type.SUCCESS || resultType == GroupedResult.Type.REJECT) {
        sb.append(result.getPerBranchesNonErrorReport());
      } else {
        sb.append(result.getOutput());
      }
      sb.append("<br/>");
    }
    return sb.toString();
  }


  private static class GroupedResult {
    enum Type {
      SUCCESS,
      REJECT,
      ERROR
    }
    
    private final Map<BzrRepository, BzrPushRepoResult> mySuccessfulResults;
    private final Map<BzrRepository, BzrPushRepoResult> myErrorResults;
    private final Map<BzrRepository, BzrPushRepoResult> myRejectedResults;

    GroupedResult(@NotNull Map<BzrRepository, BzrPushRepoResult> successfulResults,
                  @NotNull Map<BzrRepository, BzrPushRepoResult> errorResults,
                  @NotNull Map<BzrRepository, BzrPushRepoResult> rejectedResults) {
      mySuccessfulResults = successfulResults;
      myErrorResults = errorResults;
      myRejectedResults = rejectedResults;
    }
  }

  @NotNull
  private static String commits(int commitNum) {
    return commitNum + " " + StringUtil.pluralize("commit", commitNum);
  }

  private class ViewUpdatedFilesNotificationListener implements NotificationListener {
    private final UpdatedFiles myUpdatedFiles;

    public ViewUpdatedFilesNotificationListener(UpdatedFiles updatedFiles) {
      myUpdatedFiles = updatedFiles;
    }

    @Override
    public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
      if (event.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED)) {
        if (event.getDescription().equals("UpdatedFiles")) {
          ProjectLevelVcsManagerEx vcsManager = ProjectLevelVcsManagerEx.getInstanceEx(myProject);
          UpdateInfoTree tree = vcsManager.showUpdateProjectInfo(myUpdatedFiles, "Update", ActionInfo.UPDATE, false);
          tree.setBefore(myBeforeUpdateLabel);
          tree.setAfter(LocalHistory.getInstance().putSystemLabel(myProject, "After push"));
        }
        else {
          BrowserUtil.launchBrowser(event.getDescription());
        }
      }
    }
  }
}
