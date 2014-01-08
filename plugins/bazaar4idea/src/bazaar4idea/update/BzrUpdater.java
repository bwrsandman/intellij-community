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

import bazaar4idea.branch.BzrBranchPair;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.config.BzrVcsSettings;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.*;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.merge.MergeChangeCollector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Map;

/**
 * Updates a single repository via merge or rebase.
 * @see BzrRebaseUpdater
 * @see BzrMergeUpdater
 */
public abstract class BzrUpdater {
  private static final Logger LOG = Logger.getInstance(BzrUpdater.class);

  @NotNull protected final Project myProject;
  @NotNull protected final Bzr myBzr;
  @NotNull protected final VirtualFile myRoot;
  @NotNull protected final Map<VirtualFile, BzrBranchPair> myTrackedBranches;
  @NotNull protected final ProgressIndicator myProgressIndicator;
  @NotNull protected final UpdatedFiles myUpdatedFiles;
  @NotNull protected final AbstractVcsHelper myVcsHelper;
  @NotNull protected final BzrRepositoryManager myRepositoryManager;
  protected final BzrVcs myVcs;

  protected BzrRevisionNumber myBefore; // The revision that was before update

  protected BzrUpdater(@NotNull Project project,
                       @NotNull Bzr bzr,
                       @NotNull VirtualFile root,
                       @NotNull Map<VirtualFile, BzrBranchPair> trackedBranches,
                       @NotNull ProgressIndicator progressIndicator,
                       @NotNull UpdatedFiles updatedFiles) {
    myProject = project;
    myBzr = bzr;
    myRoot = root;
    myTrackedBranches = trackedBranches;
    myProgressIndicator = progressIndicator;
    myUpdatedFiles = updatedFiles;
    myVcsHelper = AbstractVcsHelper.getInstance(project);
    myVcs = BzrVcs.getInstance(project);
    myRepositoryManager = BzrUtil.getRepositoryManager(myProject);
  }

  /**
   * Returns proper updater based on the update policy (merge or rebase) selected by user or stored in his .git/config
   * @return {@link BzrMergeUpdater} or {@link BzrRebaseUpdater}.
   */
  @NotNull
  public static BzrUpdater getUpdater(@NotNull Project project, @NotNull Bzr bzr, @NotNull Map<VirtualFile, BzrBranchPair> trackedBranches,
                                      @NotNull VirtualFile root, @NotNull ProgressIndicator progressIndicator,
                                      @NotNull UpdatedFiles updatedFiles) {
    final BzrVcsSettings settings = BzrVcsSettings.getInstance(project);
    if (settings == null) {
      return getDefaultUpdaterForBranch(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
    }
    switch (settings.getUpdateType()) {
      case REBASE:
        return new BzrRebaseUpdater(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
      case MERGE:
        return new BzrMergeUpdater(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
      case BRANCH_DEFAULT:
        // use default for the branch
        return getDefaultUpdaterForBranch(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
    }
    return getDefaultUpdaterForBranch(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
  }

  @NotNull
  private static BzrUpdater getDefaultUpdaterForBranch(@NotNull Project project, @NotNull Bzr bzr, @NotNull VirtualFile root,
                                                       @NotNull Map<VirtualFile, BzrBranchPair> trackedBranches,
                                                       @NotNull ProgressIndicator progressIndicator, @NotNull UpdatedFiles updatedFiles) {
    try {
      BzrLocalBranch branch = BzrBranchUtil.getCurrentBranch(project, root);
      boolean rebase = false;
      if (branch != null) {
        String rebaseValue = BzrConfigUtil.getValue(project, root, "branch." + branch.getName() + ".rebase");
        rebase = rebaseValue != null && rebaseValue.equalsIgnoreCase("true");
      }
      if (rebase) {
        return new BzrRebaseUpdater(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
      }
    } catch (VcsException e) {
      LOG.info("getDefaultUpdaterForBranch branch", e);
    }
    return new BzrMergeUpdater(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
  }

  @NotNull
  public BzrUpdateResult update() throws VcsException {
    markStart(myRoot);
    try {
      return doUpdate();
    } finally {
      markEnd(myRoot);
    }
  }

  /**
   * Checks the repository if local changes need to be saved before update.
   * For rebase local changes need to be saved always, 
   * for merge - only in the case if merge affects the same files or there is something in the index.
   * @return true if local changes from this root need to be saved, false if not.
   */
  public abstract boolean isSaveNeeded();

  /**
   * Checks if update is needed, i.e. if there are remote changes that weren't merged into the current branch.
   * @return true if update is needed, false otherwise.
   */
  public boolean isUpdateNeeded() throws VcsException {
    BzrBranchPair bzrBranchPair = myTrackedBranches.get(myRoot);
    String currentBranch = bzrBranchPair.getBranch().getName();
    BzrBranch dest = bzrBranchPair.getDest();
    assert dest != null;
    String remoteBranch = dest.getName();
    if (! hasRemoteChanges(currentBranch, remoteBranch)) {
      LOG.info("isSaveNeeded No remote changes, save is not needed");
      return false;
    }
    return true;
  }

  /**
   * Performs update (via rebase or merge - depending on the implementing classes).
   */
  protected abstract BzrUpdateResult doUpdate();

  protected void markStart(VirtualFile root) throws VcsException {
    // remember the current position
    myBefore = BzrRevisionNumber.resolve(myProject, root, "HEAD");
  }

  protected void markEnd(VirtualFile root) throws VcsException {
    // find out what have changed, this is done even if the process was cancelled.
    final MergeChangeCollector collector = new MergeChangeCollector(myProject, root, myBefore);
    final ArrayList<VcsException> exceptions = new ArrayList<VcsException>();
    collector.collect(myUpdatedFiles, exceptions);
    if (!exceptions.isEmpty()) {
      throw exceptions.get(0);
    }
  }

  protected boolean hasRemoteChanges(@NotNull String currentBranch, @NotNull String remoteBranch) throws VcsException {
    BzrSimpleHandler handler = new BzrSimpleHandler(myProject, myRoot, BzrCommand.VERSION_INFO);
    //handler.setSilent(true);
    handler.addParameters(currentBranch + ".." + remoteBranch);
    String output = handler.run();
    return output != null && !output.isEmpty();
  }

  @NotNull
  protected String makeProgressTitle(@NotNull String operation) {
    return myRepositoryManager.moreThanOneRoot() ? String.format("%s %s...", operation, myRoot.getName()) : operation + "...";
  }

}
