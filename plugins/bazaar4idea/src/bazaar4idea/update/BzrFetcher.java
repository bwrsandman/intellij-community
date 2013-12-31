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
package bazaar4idea.update;

import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.config.BzrVersionSpecialty;
import bazaar4idea.repo.BzrBranchTrackInfo;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.*;
import bazaar4idea.commands.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static bazaar4idea.BzrBranch.REFS_HEADS_PREFIX;
import static bazaar4idea.BzrBranch.REFS_REMOTES_PREFIX;

/**
 * @author Kirill Likhodedov
 */
public class BzrFetcher {

  private static final Logger LOG = Logger.getInstance(BzrFetcher.class);

  private final Project myProject;
  private final BzrRepositoryManager myRepositoryManager;
  private final ProgressIndicator myProgressIndicator;
  private final boolean myFetchAll;
  private final BzrVcs myVcs;

  private final Collection<Exception> myErrors = new ArrayList<Exception>();

  /**
   * @param fetchAll Pass {@code true} to fetch all remotes and all branches (like {@code git fetch} without parameters does).
   *                 Pass {@code false} to fetch only the tracked branch of the current branch.
   */
  public BzrFetcher(@NotNull Project project, @NotNull ProgressIndicator progressIndicator, boolean fetchAll) {
    myProject = project;
    myProgressIndicator = progressIndicator;
    myFetchAll = fetchAll;
    myRepositoryManager = BzrUtil.getRepositoryManager(myProject);
    myVcs = BzrVcs.getInstance(project);
  }

  /**
   * Invokes 'git fetch'.
   * @return true if fetch was successful, false in the case of error.
   */
  public BzrFetchResult fetch(@NotNull BzrRepository repository) {
    // TODO need to have a fair compound result here
    BzrFetchResult fetchResult = BzrFetchResult.success();
    if (myFetchAll) {
      fetchResult = fetchAll(repository, fetchResult);
    }
    else {
      return fetchCurrentRemote(repository);
    }

    repository.update();
    return fetchResult;
  }

  @NotNull
  public BzrFetchResult fetch(@NotNull VirtualFile root, @NotNull String remoteName, @Nullable String branch) {
    BzrRepository repository = myRepositoryManager.getRepositoryForRoot(root);
    if (repository == null) {
      return logError("Repository can't be null for " + root, myRepositoryManager.toString());
    }
    BzrRemote remote = BzrUtil.findRemoteByName(repository, remoteName);
    if (remote == null) {
      return logError("Couldn't find remote with the name " + remoteName, null);
    }
    String url = remote.getFirstUrl();
    if (url == null) {
      return logError("URL is null for remote " + remote.getName(), null);
    }
    return fetchRemote(repository, remote, url, branch);
  }

  private static BzrFetchResult logError(@NotNull String message, @Nullable String additionalInfo) {
    String addInfo = additionalInfo != null ? "\n" + additionalInfo : "";
    LOG.error(message + addInfo);
    return BzrFetchResult.error(message);
  }

  @NotNull
  private BzrFetchResult fetchCurrentRemote(@NotNull BzrRepository repository) {
    FetchParams fetchParams = getFetchParams(repository);
    if (fetchParams.isError()) {
      return fetchParams.getError();
    }

    BzrRemote remote = fetchParams.getRemote();
    String url = fetchParams.getUrl();
    return fetchRemote(repository, remote, url, null);
  }

  @NotNull
  private BzrFetchResult fetchRemote(@NotNull BzrRepository repository,
                                     @NotNull BzrRemote remote,
                                     @NotNull String url,
                                     @Nullable String branch) {
    return fetchNatively(repository.getRoot(), remote, url, branch);
  }

  // leaving this unused method, because the wanted behavior can change again
  @SuppressWarnings("UnusedDeclaration")
  @NotNull
  private BzrFetchResult fetchCurrentBranch(@NotNull BzrRepository repository) {
    FetchParams fetchParams = getFetchParams(repository);
    if (fetchParams.isError()) {
      return fetchParams.getError();
    }

    BzrRemote remote = fetchParams.getRemote();
    String remoteBranch = fetchParams.getRemoteBranch().getNameForRemoteOperations();
    String url = fetchParams.getUrl();
    return fetchNatively(repository.getRoot(), remote, url, remoteBranch);
  }

  @NotNull
  private static FetchParams getFetchParams(@NotNull BzrRepository repository) {
    BzrLocalBranch currentBranch = repository.getCurrentBranch();
    if (currentBranch == null) {
      // fetching current branch is called from Update Project and Push, where branch tracking is pre-checked
      String message = "Current branch can't be null here. \nRepository: " + repository;
      LOG.error(message);
      return new FetchParams(BzrFetchResult.error(new Exception(message)));
    }
    BzrBranchTrackInfo trackInfo = BzrBranchUtil.getTrackInfoForBranch(repository, currentBranch);
    if (trackInfo == null) {
      String message = "Tracked info is null for branch " + currentBranch + "\n Repository: " + repository;
      LOG.error(message);
      return new FetchParams(BzrFetchResult.error(new Exception(message)));
    }

    BzrRemote remote = trackInfo.getRemote();
    String url = remote.getFirstUrl();
    if (url == null) {
      String message = "URL is null for remote " + remote.getName();
      LOG.error(message);
      return new FetchParams(BzrFetchResult.error(new Exception(message)));
    }

    return new FetchParams(remote, trackInfo.getRemoteBranch(), url);
  }

  @NotNull
  private BzrFetchResult fetchAll(@NotNull BzrRepository repository, @NotNull BzrFetchResult fetchResult) {
    for (BzrRemote remote : repository.getRemotes()) {
      String url = remote.getFirstUrl();
      if (url == null) {
        LOG.error("URL is null for remote " + remote.getName());
        continue;
      }
      BzrFetchResult res = fetchNatively(repository.getRoot(), remote, url, null);
      res.addPruneInfo(fetchResult.getPrunedRefs());
      fetchResult = res;
      if (!fetchResult.isSuccess()) {
        break;
      }
    }
    return fetchResult;
  }

  private BzrFetchResult fetchNatively(@NotNull VirtualFile root, @NotNull BzrRemote remote, @NotNull String url, @Nullable String branch) {
    final BzrLineHandlerPasswordRequestAware h = new BzrLineHandlerPasswordRequestAware(myProject, root, BzrCommand.FETCH);
    h.setUrl(url);
    h.addProgressParameter();

    String remoteName = remote.getName();
    h.addParameters(remoteName);
    if (branch != null) {
      h.addParameters(getFetchSpecForBranch(branch, remoteName));
    }

    final BzrTask fetchTask = new BzrTask(myProject, h, "Fetching " + remote.getFirstUrl());
    fetchTask.setProgressIndicator(myProgressIndicator);
    fetchTask.setProgressAnalyzer(new BzrStandardProgressAnalyzer());

    BzrFetchPruneDetector pruneDetector = new BzrFetchPruneDetector();
    h.addLineListener(pruneDetector);

    final AtomicReference<BzrFetchResult> result = new AtomicReference<BzrFetchResult>();
    fetchTask.execute(true, false, new BzrTaskResultHandlerAdapter() {
      @Override
      protected void onSuccess() {
        result.set(BzrFetchResult.success());
      }

      @Override
      protected void onCancel() {
        LOG.info("Cancelled fetch.");
        result.set(BzrFetchResult.cancel());
      }
      
      @Override
      protected void onFailure() {
        LOG.info("Error fetching: " + h.errors());
        if (!h.hadAuthRequest()) {
          myErrors.addAll(h.errors());
        } else {
          myErrors.add(new VcsException("Authentication failed"));
        }
        result.set(BzrFetchResult.error(myErrors));
      }
    });

    result.get().addPruneInfo(pruneDetector.getPrunedRefs());
    return result.get();
  }

  private static String getRidOfPrefixIfExists(String branch) {
    if (branch.startsWith(REFS_HEADS_PREFIX)) {
      return branch.substring(REFS_HEADS_PREFIX.length());
    }
    return branch;
  }

  @NotNull
  public static String getFetchSpecForBranch(@NotNull String branch, @NotNull String remoteName) {
    branch = getRidOfPrefixIfExists(branch);
    return REFS_HEADS_PREFIX + branch + ":" + REFS_REMOTES_PREFIX + remoteName + "/" + branch;
  }

  @NotNull
  public Collection<Exception> getErrors() {
    return myErrors;
  }

  public static void displayFetchResult(@NotNull Project project,
                                        @NotNull BzrFetchResult result,
                                        @Nullable String errorNotificationTitle, @NotNull Collection<? extends Exception> errors) {
    if (result.isSuccess()) {
      BzrVcs.NOTIFICATION_GROUP_ID.createNotification("Fetched successfully" + result.getAdditionalInfo(), NotificationType.INFORMATION).notify(project);
    } else if (result.isCancelled()) {
      BzrVcs.NOTIFICATION_GROUP_ID.createNotification("Fetch cancelled by user" + result.getAdditionalInfo(), NotificationType.WARNING).notify(project);
    } else if (result.isNotAuthorized()) {
      String title;
      String description;
      if (errorNotificationTitle != null) {
        title = errorNotificationTitle;
        description = "Fetch failed: couldn't authorize";
      } else {
        title = "Fetch failed";
        description = "Couldn't authorize";
      }
      description += result.getAdditionalInfo();
      BzrUIUtil.notifyMessage(project, title, description, NotificationType.ERROR, true, null);
    } else {
      BzrVcs instance = BzrVcs.getInstance(project);
      if (instance != null && instance.getExecutableValidator().isExecutableValid()) {
        BzrUIUtil.notifyMessage(project, "Fetch failed", result.getAdditionalInfo(), NotificationType.ERROR, true, errors);
      }
    }
  }

  /**
   * Fetches all specified roots.
   * Once a root has failed, stops and displays the notification.
   * If needed, displays the successful notification at the end.
   * @param roots                   roots to fetch.
   * @param errorNotificationTitle  if specified, this notification title will be used instead of the standard "Fetch failed".
   *                                Use this when fetch is a part of a compound process.
   * @param notifySuccess           if set to {@code true} successful notification will be displayed.
   * @return true if all fetches were successful, false if at least one fetch failed.
   */
  public boolean fetchRootsAndNotify(@NotNull Collection<BzrRepository> roots,
                                     @Nullable String errorNotificationTitle, boolean notifySuccess) {
    Map<VirtualFile, String> additionalInfo = new HashMap<VirtualFile, String>();
    for (BzrRepository repository : roots) {
      LOG.info("fetching " + repository);
      BzrFetchResult result = fetch(repository);
      String ai = result.getAdditionalInfo();
      if (!StringUtil.isEmptyOrSpaces(ai)) {
        additionalInfo.put(repository.getRoot(), ai);
      }
      if (!result.isSuccess()) {
        Collection<Exception> errors = new ArrayList<Exception>(getErrors());
        errors.addAll(result.getErrors());
        displayFetchResult(myProject, result, errorNotificationTitle, errors);
        return false;
      }
    }
    if (notifySuccess) {
      BzrUIUtil.notifySuccess(myProject, "", "Fetched successfully");
    }

    String addInfo = makeAdditionalInfoByRoot(additionalInfo);
    if (!StringUtil.isEmptyOrSpaces(addInfo)) {
        Notificator.getInstance(myProject).notify(BzrVcs.MINOR_NOTIFICATION, "Fetch details", addInfo, NotificationType.INFORMATION);
    }

    return true;
  }

  @NotNull
  private String makeAdditionalInfoByRoot(@NotNull Map<VirtualFile, String> additionalInfo) {
    if (additionalInfo.isEmpty()) {
      return "";
    }
    StringBuilder info = new StringBuilder();
    if (myRepositoryManager.moreThanOneRoot()) {
      for (Map.Entry<VirtualFile, String> entry : additionalInfo.entrySet()) {
        info.append(entry.getValue()).append(" in ").append(DvcsUtil.getShortRepositoryName(myProject, entry.getKey())).append("<br/>");
      }
    }
    else {
      info.append(additionalInfo.values().iterator().next());
    }
    return info.toString();
  }

  private static class BzrFetchPruneDetector extends BzrLineHandlerAdapter {

    private static final Pattern PRUNE_PATTERN = Pattern.compile("\\s*x\\s*\\[deleted\\].*->\\s*(\\S*)");

    @NotNull private final Collection<String> myPrunedRefs = new ArrayList<String>();

    @Override
    public void onLineAvailable(String line, Key outputType) {
      //  x [deleted]         (none)     -> origin/frmari
      Matcher matcher = PRUNE_PATTERN.matcher(line);
      if (matcher.matches()) {
        myPrunedRefs.add(matcher.group(1));
      }
    }

    @NotNull
    public Collection<String> getPrunedRefs() {
      return myPrunedRefs;
    }
  }

  private static class FetchParams {
    private BzrRemote myRemote;
    private BzrRemoteBranch myRemoteBranch;
    private BzrFetchResult myError;
    private String myUrl;

    FetchParams(BzrFetchResult error) {
      myError = error;
    }

    FetchParams(BzrRemote remote, BzrRemoteBranch remoteBranch, String url) {
      myRemote = remote;
      myRemoteBranch = remoteBranch;
      myUrl = url;
    }

    boolean isError() {
      return myError != null;
    }

    public BzrFetchResult getError() {
      return myError;
    }

    public BzrRemote getRemote() {
      return myRemote;
    }

    public BzrRemoteBranch getRemoteBranch() {
      return myRemoteBranch;
    }

    public String getUrl() {
      return myUrl;
    }
  }
}
