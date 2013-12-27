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

import bazaar4idea.BzrUtil;
import bazaar4idea.branch.BzrBranchPair;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.rebase.BzrRebaser;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;
import bazaar4idea.BzrBranch;
import bazaar4idea.Notificator;
import bazaar4idea.commands.Bzr;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Handles 'git pull --rebase'
 */
public class BzrRebaseUpdater extends BzrUpdater {
  private static final Logger LOG = Logger.getInstance(BzrRebaseUpdater.class.getName());
  private final BzrRebaser myRebaser;

  public BzrRebaseUpdater(@NotNull Project project,
                          @NotNull Bzr bzr,
                          @NotNull VirtualFile root,
                          @NotNull final Map<VirtualFile, BzrBranchPair> trackedBranches,
                          ProgressIndicator progressIndicator,
                          UpdatedFiles updatedFiles) {
    super(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
    myRebaser = new BzrRebaser(myProject, bzr, myProgressIndicator);
  }

  @Override public boolean isSaveNeeded() {
    return true;
  }

  protected BzrUpdateResult doUpdate() {
    LOG.info("doUpdate ");
    String remoteBranch = getRemoteBranchToMerge();
    List<String> params = Arrays.asList(remoteBranch);
    return myRebaser.rebase(myRoot, params, new Runnable() {
      @Override
      public void run() {
        cancel();
      }
    }, null);
  }

  @NotNull
  private String getRemoteBranchToMerge() {
    BzrBranchPair bzrBranchPair = myTrackedBranches.get(myRoot);
    BzrBranch dest = bzrBranchPair.getDest();
    LOG.assertTrue(dest != null, String.format("Destination branch is null for source branch %s in %s",
                                               bzrBranchPair.getBranch().getName(), myRoot));
    return dest.getName();
  }

  // TODO
    //if (!checkLocallyModified(myRoot)) {
    //  cancel();
    //  updateSucceeded.set(false);
    //}


    // TODO: show at any case of update successfullibility, also don't show here but for all roots
    //if (mySkippedCommits.size() > 0) {
    //  BzrSkippedCommits.showSkipped(myProject, mySkippedCommits);
    //}

  public void cancel() {
    myRebaser.abortRebase(myRoot);
    myProgressIndicator.setText2("Refreshing files for the root " + myRoot.getPath());
    myRoot.refresh(false, true);
  }

  /**
   * Check and process locally modified files
   *
   * @param root      the project root
   * @param ex        the exception holder
   */
  protected boolean checkLocallyModified(final VirtualFile root) throws VcsException {
    final Ref<Boolean> cancelled = new Ref<Boolean>(false);
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      public void run() {
        if (!BzrUpdateLocallyModifiedDialog.showIfNeeded(myProject, root)) {
          cancelled.set(true);
        }
      }
    });
    return !cancelled.get();
  }

  @Override
  public String toString() {
    return "Rebase updater";
  }

  /**
   * Tries to execute {@code git merge --ff-only}.
   * @return true, if everything is successful; false for any error (to let a usual "fair" update deal with it).
   */
  public boolean fastForwardMerge() {
    LOG.info("Trying fast-forward merge for " + myRoot);
    BzrRepository repository = BzrUtil.getRepositoryManager(myProject).getRepositoryForRoot(myRoot);
    if (repository == null) {
      LOG.error("Repository is null for " + myRoot);
      return false;
    }
    try {
      markStart(myRoot);
    }
    catch (VcsException e) {
      LOG.info("Couldn't mark start for repository " + myRoot, e);
      return false;
    }

    BzrCommandResult result = myBzr.merge(repository, getRemoteBranchToMerge(), Collections.singletonList("--ff-only"));

    try {
      markEnd(myRoot);
    }
    catch (VcsException e) {
      // this is not critical, and update has already happened,
      // so we just notify the user about problems with collecting the updated changes.
      LOG.info("Couldn't mark end for repository " + myRoot, e);
      Notificator.getInstance(myProject).
        notifyWeakWarning("Couldn't collect the updated files info",
                          String.format("Update of %s was successful, but we couldn't collect the updated changes because of an error",
                                        myRoot), null);
    }
    return result.success();
  }

}
