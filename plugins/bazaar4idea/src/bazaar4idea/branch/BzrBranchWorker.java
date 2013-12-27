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
import bazaar4idea.BzrExecutionException;
import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.changes.BzrChangeUtils;
import bazaar4idea.commands.Bzr;
import bazaar4idea.history.BzrHistoryUtils;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.ui.branch.BzrCompareBranchesDialog;
import bazaar4idea.util.BzrCommitCompareInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the logic of git branch operations.
 * All operations are run in the current thread.
 * All UI interaction is done via the {@link BzrBranchUiHandler} passed to the constructor.
 *
 * @author Kirill Likhodedov
 */
public final class BzrBranchWorker {

  private static final Logger LOG = Logger.getInstance(BzrBranchWorker.class);

  @NotNull private final Project myProject;
  @NotNull private final BzrPlatformFacade myFacade;
  @NotNull private final Bzr myBzr;
  @NotNull private final BzrBranchUiHandler myUiHandler;

  public BzrBranchWorker(@NotNull Project project,
                         @NotNull BzrPlatformFacade facade,
                         @NotNull Bzr bzr,
                         @NotNull BzrBranchUiHandler uiHandler) {
    myProject = project;
    myFacade = facade;
    myBzr = bzr;
    myUiHandler = uiHandler;
  }
  
  public void checkoutNewBranch(@NotNull final String name, @NotNull final List<BzrRepository> repositories) {
    updateInfo(repositories);
    new BzrCheckoutNewBranchOperation(myProject, myFacade, myBzr, myUiHandler, repositories, name).execute();
  }

  public void createNewTag(@NotNull final String name, @NotNull final String reference, @NotNull final List<BzrRepository> repositories) {
    updateInfo(repositories);
    for (BzrRepository repository : repositories) {
      myBzr.createNewTag(repository, name, null, reference);
    }
  }

  public void checkoutNewBranchStartingFrom(@NotNull String newBranchName, @NotNull String startPoint,
                                            @NotNull List<BzrRepository> repositories) {
    updateInfo(repositories);
    new BzrCheckoutOperation(myProject, myFacade, myBzr, myUiHandler, repositories, startPoint, newBranchName).execute();
  }

  public void checkout(@NotNull final String reference, @NotNull List<BzrRepository> repositories) {
    updateInfo(repositories);
    new BzrCheckoutOperation(myProject, myFacade, myBzr, myUiHandler, repositories, reference, null).execute();
  }


  public void deleteBranch(@NotNull final String branchName, @NotNull final List<BzrRepository> repositories) {
    updateInfo(repositories);
    new BzrDeleteBranchOperation(myProject, myFacade, myBzr, myUiHandler, repositories, branchName).execute();
  }

  public void deleteRemoteBranch(@NotNull final String branchName, @NotNull final List<BzrRepository> repositories) {
    updateInfo(repositories);
    new BzrDeleteRemoteBranchOperation(myProject, myFacade, myBzr, myUiHandler, repositories, branchName).execute();
  }

  public void merge(@NotNull final String branchName, @NotNull final BzrBrancher.DeleteOnMergeOption deleteOnMerge,
                    @NotNull final List<BzrRepository> repositories) {
    updateInfo(repositories);
    Map<BzrRepository, String> revisions = new HashMap<BzrRepository, String>();
    for (BzrRepository repository : repositories) {
      revisions.put(repository, repository.getCurrentRevision());
    }
    new BzrMergeOperation(myProject, myFacade, myBzr, myUiHandler, repositories, branchName, deleteOnMerge, revisions).execute();
  }

  public void compare(@NotNull final String branchName, @NotNull final List<BzrRepository> repositories,
                      @NotNull final BzrRepository selectedRepository) {
    final BzrCommitCompareInfo myCompareInfo = loadCommitsToCompare(repositories, branchName);
    if (myCompareInfo == null) {
      LOG.error("The task to get compare info didn't finish. Repositories: \n" + repositories + "\nbranch name: " + branchName);
      return;
    }
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        displayCompareDialog(branchName, BzrBranchUtil.getCurrentBranchOrRev(repositories), myCompareInfo, selectedRepository);
      }
    });
  }

  private BzrCommitCompareInfo loadCommitsToCompare(List<BzrRepository> repositories, String branchName) {
    BzrCommitCompareInfo compareInfo = new BzrCommitCompareInfo();
    for (BzrRepository repository : repositories) {
      compareInfo.put(repository, loadCommitsToCompare(repository, branchName));
      compareInfo.put(repository, loadTotalDiff(repository, branchName));
    }
    return compareInfo;
  }

  @NotNull
  private static Collection<Change> loadTotalDiff(@NotNull BzrRepository repository, @NotNull String branchName) {
    try {
      return BzrChangeUtils.getDiff(repository.getProject(), repository.getRoot(), null, branchName, null);
    }
    catch (VcsException e) {
      // we treat it as critical and report an error
      throw new BzrExecutionException("Couldn't get [git diff " + branchName + "] on repository [" + repository.getRoot() + "]", e);
    }
  }

  @NotNull
  private Pair<List<BzrCommit>, List<BzrCommit>> loadCommitsToCompare(@NotNull BzrRepository repository, @NotNull final String branchName) {
    final List<BzrCommit> headToBranch;
    final List<BzrCommit> branchToHead;
    try {
      headToBranch = BzrHistoryUtils.history(myProject, repository.getRoot(), ".." + branchName);
      branchToHead = BzrHistoryUtils.history(myProject, repository.getRoot(), branchName + "..");
    }
    catch (VcsException e) {
      // we treat it as critical and report an error
      throw new BzrExecutionException("Couldn't get [git log .." + branchName + "] on repository [" + repository.getRoot() + "]", e);
    }
    return Pair.create(headToBranch, branchToHead);
  }
  
  private void displayCompareDialog(@NotNull String branchName, @NotNull String currentBranch, @NotNull BzrCommitCompareInfo compareInfo,
                                    @NotNull BzrRepository selectedRepository) {
    if (compareInfo.isEmpty()) {
      Messages.showInfoMessage(myProject, String.format("<html>There are no changes between <code>%s</code> and <code>%s</code></html>",
                                                        currentBranch, branchName), "No Changes Detected");
    }
    else {
      new BzrCompareBranchesDialog(myProject, branchName, currentBranch, compareInfo, selectedRepository).show();
    }
  }

  private static void updateInfo(@NotNull Collection<BzrRepository> repositories) {
    for (BzrRepository repository : repositories) {
      repository.update();
    }
  }

}
