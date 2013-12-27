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

import bazaar4idea.BzrCommit;
import bazaar4idea.BzrVcs;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.commands.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deletes a branch.
 * If branch is not fully merged to the current branch, shows a dialog with the list of unmerged commits and with a list of branches
 * current branch are merged to, and makes force delete, if wanted.
 *
 * @author Kirill Likhodedov
 */
class BzrDeleteBranchOperation extends BzrBranchOperation {
  
  private static final Logger LOG = Logger.getInstance(BzrDeleteBranchOperation.class);

  private final String myBranchName;

  BzrDeleteBranchOperation(@NotNull Project project,
                           BzrPlatformFacade facade,
                           @NotNull Bzr bzr,
                           @NotNull BzrBranchUiHandler uiHandler,
                           @NotNull Collection<BzrRepository> repositories,
                           @NotNull String branchName) {
    super(project, facade, bzr, uiHandler, repositories);
    myBranchName = branchName;
  }

  @Override
  public void execute() {
    boolean fatalErrorHappened = false;
    while (hasMoreRepositories() && !fatalErrorHappened) {
      final BzrRepository repository = next();

      BzrSimpleEventDetector notFullyMergedDetector = new BzrSimpleEventDetector(BzrSimpleEventDetector.Event.BRANCH_NOT_FULLY_MERGED);
      BzrBranchNotMergedToUpstreamDetector notMergedToUpstreamDetector = new BzrBranchNotMergedToUpstreamDetector();
      BzrCommandResult result = myBzr.branchDelete(repository, myBranchName, false, notFullyMergedDetector, notMergedToUpstreamDetector);

      if (result.success()) {
        refresh(repository);
        markSuccessful(repository);
      }
      else if (notFullyMergedDetector.hasHappened()) {
        String baseBranch = notMergedToUpstreamDetector.getBaseBranch();
        if (baseBranch == null) { // BzrBranchNotMergedToUpstreamDetector didn't happen
          baseBranch = myCurrentBranchOrRev;
        }

        Collection<BzrRepository> remainingRepositories = getRemainingRepositories();
        boolean forceDelete = showNotFullyMergedDialog(myBranchName, baseBranch, remainingRepositories);
        if (forceDelete) {
          BzrCompoundResult compoundResult = forceDelete(myBranchName, remainingRepositories);
          if (compoundResult.totalSuccess()) {
            BzrRepository[] remainingRepositoriesArray = ArrayUtil.toObjectArray(remainingRepositories, BzrRepository.class);
            markSuccessful(remainingRepositoriesArray);
            refresh(remainingRepositoriesArray);
          }
          else {
            fatalError(getErrorTitle(), compoundResult.getErrorOutputWithReposIndication());
            return;
          }
        }
        else {
          if (wereSuccessful())  {
            showFatalErrorDialogWithRollback(getErrorTitle(), "This branch is not fully merged to " + baseBranch + ".");
          }
          fatalErrorHappened = true;
        }
      }
      else {
        fatalError(getErrorTitle(), result.getErrorOutputAsJoinedString());
        fatalErrorHappened = true;
      }
    }

    if (!fatalErrorHappened) {
      notifySuccess();
    }
  }

  private static void refresh(@NotNull BzrRepository... repositories) {
    for (BzrRepository repository : repositories) {
      repository.update();
    }
  }

  @Override
  protected void rollback() {
    BzrCompoundResult result = new BzrCompoundResult(myProject);
    for (BzrRepository repository : getSuccessfulRepositories()) {
      BzrCommandResult res = myBzr.branchCreate(repository, myBranchName);
      result.append(repository, res);
      refresh(repository);
    }

    if (!result.totalSuccess()) {
      BzrUIUtil.notify(BzrVcs.IMPORTANT_ERROR_NOTIFICATION, myProject, "Error during rollback of branch deletion",
                       result.getErrorOutputWithReposIndication(), NotificationType.ERROR, null);
    }
  }

  @NotNull
  private String getErrorTitle() {
    return String.format("Branch %s wasn't deleted", myBranchName);
  }

  @NotNull
  public String getSuccessMessage() {
    return String.format("Deleted branch <b><code>%s</code></b>", myBranchName);
  }

  @NotNull
  @Override
  protected String getRollbackProposal() {
    return "However branch deletion has succeeded for the following " + repositories() + ":<br/>" +
           successfulRepositoriesJoined() +
           "<br/>You may rollback (recreate " + myBranchName + " in these roots) not to let branches diverge.";
  }

  @NotNull
  @Override
  protected String getOperationName() {
    return "branch deletion";
  }

  @NotNull
  private BzrCompoundResult forceDelete(@NotNull String branchName, @NotNull Collection<BzrRepository> possibleFailedRepositories) {
    BzrCompoundResult compoundResult = new BzrCompoundResult(myProject);
    for (BzrRepository repository : possibleFailedRepositories) {
      BzrCommandResult res = myBzr.branchDelete(repository, branchName, true);
      compoundResult.append(repository, res);
    }
    return compoundResult;
  }

  /**
   * Shows a dialog "the branch is not fully merged" with the list of unmerged commits.
   * User may still want to force delete the branch.
   * In multi-repository setup collects unmerged commits for all given repositories.
   * @return true if the branch should be force deleted.
   */
  private boolean showNotFullyMergedDialog(@NotNull final String unmergedBranch, @NotNull final String baseBranch,
                                           @NotNull Collection<BzrRepository> repositories) {
    final List<String> mergedToBranches = getMergedToBranches(unmergedBranch);

    final Map<BzrRepository, List<BzrCommit>> history = new HashMap<BzrRepository, List<BzrCommit>>();

    // note getRepositories() instead of getRemainingRepositories() here:
    // we don't confuse user with the absence of repositories that have succeeded, just show no commits for them (and don't query for log)
    for (BzrRepository repository : getRepositories()) {
      if (repositories.contains(repository)) {
        history.put(repository, getUnmergedCommits(repository, unmergedBranch, baseBranch));
      }
      else {
        history.put(repository, Collections.<BzrCommit>emptyList());
      }
    }

    return myUiHandler.showBranchIsNotFullyMergedDialog(myProject, history, unmergedBranch, mergedToBranches, baseBranch);
  }

  @NotNull
  private List<BzrCommit> getUnmergedCommits(@NotNull BzrRepository repository, @NotNull String branchName, @NotNull String baseBranch) {
    return myBzr.history(repository, baseBranch + ".." + branchName);
  }

  @NotNull
  private List<String> getMergedToBranches(String branchName) {
    List<String> mergedToBranches = null;
    for (BzrRepository repository : getRemainingRepositories()) {
      List<String> branches = getMergedToBranches(repository, branchName);
      if (mergedToBranches == null) {
        mergedToBranches = branches;
      } 
      else {
        mergedToBranches = new ArrayList<String>(ContainerUtil.intersection(mergedToBranches, branches));
      }
    }
    return mergedToBranches != null ? mergedToBranches : new ArrayList<String>();
  }

  /**
   * Branches which the given branch is merged to ({@code git branch --merged},
   * except the given branch itself.
   */
  @NotNull
  private List<String> getMergedToBranches(@NotNull BzrRepository repository, @NotNull String branchName) {
    String tip = tip(repository, branchName);
    if (tip == null) {
      return Collections.emptyList();
    }
    return branchContainsCommit(repository, tip, branchName);
  }

  @Nullable
  private String tip(BzrRepository repository, @NotNull String branchName) {
    BzrCommandResult result = myBzr.tip(repository, branchName);
    if (result.success() && result.getOutput().size() == 1) {
      return result.getOutput().get(0).trim();
    }
    // failing in this method is not critical - it is just additional information. So we just log the error
    LOG.info("Failed to get [git rev-list -1] for branch [" + branchName + "]. " + result);
    return null;
  }

  @NotNull
  private List<String> branchContainsCommit(@NotNull BzrRepository repository, @NotNull String tip, @NotNull String branchName) {
    BzrCommandResult result = myBzr.branchContains(repository, tip);
    if (result.success()) {
      List<String> branches = new ArrayList<String>();
      for (String s : result.getOutput()) {
        s = s.trim();
        if (s.startsWith("*")) {
          s = s.substring(2);
        }
        if (!s.equals(branchName)) { // this branch contains itself - not interesting
          branches.add(s);
        }
      }
      return branches;
    }

    // failing in this method is not critical - it is just additional information. So we just log the error
    LOG.info("Failed to get [git branch --contains] for hash [" + tip + "]. " + result);
    return Collections.emptyList();
  }

  // warning: not deleting branch 'feature' that is not yet merged to
  //          'refs/remotes/origin/feature', even though it is merged to HEAD.
  // error: The branch 'feature' is not fully merged.
  // If you are sure you want to delete it, run 'git branch -D feature'.
  private static class BzrBranchNotMergedToUpstreamDetector implements BzrLineHandlerListener {

    private static final Pattern PATTERN = Pattern.compile(".*'(.*)', even though it is merged to.*");
    @Nullable private String myBaseBranch;

    @Override
    public void onLineAvailable(String line, Key outputType) {
      Matcher matcher = PATTERN.matcher(line);
      if (matcher.matches()) {
        myBaseBranch = matcher.group(1);
      }
    }

    @Override
    public void processTerminated(int exitCode) {
    }

    @Override
    public void startFailed(Throwable exception) {
    }

    @Nullable
    public String getBaseBranch() {
      return myBaseBranch;
    }
  }
}
