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

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrUtil;
import bazaar4idea.branch.BzrBranchPair;
import bazaar4idea.commands.*;
import bazaar4idea.merge.BzrConflictResolver;
import bazaar4idea.merge.BzrMerger;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.util.BzrUIUtil;
import bazaar4idea.util.UntrackedFilesNotifier;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FilePathImpl;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.changes.ui.ChangeListViewerDialog;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles <code>bzr pull</code> via merge.
 */
public class BzrMergeUpdater extends BzrUpdater {
  private static final Logger LOG = Logger.getInstance(BzrMergeUpdater.class);

  private final ChangeListManager myChangeListManager;

  public BzrMergeUpdater(Project project,
                         @NotNull Bzr bzr,
                         VirtualFile root,
                         final Map<VirtualFile, BzrBranchPair> trackedBranches,
                         ProgressIndicator progressIndicator,
                         UpdatedFiles updatedFiles) {
    super(project, bzr, root, trackedBranches, progressIndicator, updatedFiles);
    myChangeListManager = ChangeListManager.getInstance(myProject);
  }

  @Override
  @NotNull
  protected BzrUpdateResult doUpdate() {
    LOG.info("doUpdate ");
    final BzrMerger merger = new BzrMerger(myProject);
    final BzrLineHandler mergeHandler = new BzrLineHandler(myProject, myRoot, BzrCommand.MERGE);
    mergeHandler.addParameters("--no-stat", "-v");
    mergeHandler.addParameters(myTrackedBranches.get(myRoot).getDest().getName());

    final MergeLineListener mergeLineListener = new MergeLineListener();
    mergeHandler.addLineListener(mergeLineListener);
    BzrUntrackedFilesOverwrittenByOperationDetector untrackedFilesDetector = new BzrUntrackedFilesOverwrittenByOperationDetector(myRoot);
    mergeHandler.addLineListener(untrackedFilesDetector);

    String progressTitle = makeProgressTitle("Merging");
    final BzrTask mergeTask = new BzrTask(myProject, mergeHandler, progressTitle);
    mergeTask.setProgressIndicator(myProgressIndicator);
    mergeTask.setProgressAnalyzer(new BzrStandardProgressAnalyzer());
    final AtomicReference<BzrUpdateResult> updateResult = new AtomicReference<BzrUpdateResult>();
    final AtomicBoolean failure = new AtomicBoolean();
    mergeTask.executeInBackground(true, new BzrTaskResultHandlerAdapter() {
      @Override protected void onSuccess() {
        updateResult.set(BzrUpdateResult.SUCCESS);
      }

      @Override protected void onCancel() {
        cancel();
        updateResult.set(BzrUpdateResult.CANCEL);
      }

      @Override protected void onFailure() {
        failure.set(true);
      }
    });

    if (failure.get()) {
      updateResult.set(handleMergeFailure(mergeLineListener, untrackedFilesDetector, merger, mergeHandler));
    }
    return updateResult.get();
  }

  @NotNull
  private BzrUpdateResult handleMergeFailure(MergeLineListener mergeLineListener,
                                             BzrMessageWithFilesDetector untrackedFilesWouldBeOverwrittenByMergeDetector,
                                             final BzrMerger merger,
                                             BzrLineHandler mergeHandler) {
    final MergeError error = mergeLineListener.getMergeError();
    LOG.info("merge error: " + error);
    if (error == MergeError.CONFLICT) {
      LOG.info("Conflict detected");
      final boolean allMerged =
        new MyConflictResolver(myProject, myBzr, merger, myRoot).merge();
      return allMerged ? BzrUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS : BzrUpdateResult.INCOMPLETE;
    }
    else if (error == MergeError.LOCAL_CHANGES) {
      LOG.info("Local changes would be overwritten by merge");
      final List<FilePath> paths = getFilesOverwrittenByMerge(mergeLineListener.getOutput());
      final Collection<Change> changes = getLocalChangesFilteredByFiles(paths);
      final ChangeListViewerDialog dialog = new ChangeListViewerDialog(myProject, changes, false) {
        @Override protected String getDescription() {
          return "Your local changes to the following files would be overwritten by merge.<br/>" +
                            "Please, commit your changes or stash them before you can merge.";
        }
      };
      UIUtil.invokeAndWaitIfNeeded(new Runnable() {
        @Override
        public void run() {
          dialog.show();
        }
      });
      return BzrUpdateResult.ERROR;
    }
    else if (untrackedFilesWouldBeOverwrittenByMergeDetector.wasMessageDetected()) {
      LOG.info("handleMergeFailure: untracked files would be overwritten by merge");
      UntrackedFilesNotifier.notifyUntrackedFilesOverwrittenBy(myProject, ServiceManager.getService(myProject, BzrPlatformFacade.class),
                                                               untrackedFilesWouldBeOverwrittenByMergeDetector.getFiles(), "merge", null);
      return BzrUpdateResult.ERROR;
    }
    else {
      String errors = BzrUIUtil.stringifyErrors(mergeHandler.errors());
      LOG.info("Unknown error: " + errors);
      BzrUIUtil.notifyImportantError(myProject, "Error merging", errors);
      return BzrUpdateResult.ERROR;
    }
  }

  @Override
  public boolean isSaveNeeded() {
    try {
      if (BzrUtil.hasLocalChanges(true, myProject, myRoot)) {
        return true;
      }
    }
    catch (VcsException e) {
      LOG.info("isSaveNeeded failed to check staging area", e);
      return true;
    }

    // git log --name-status master..origin/master
    BzrBranchPair bzrBranchPair = myTrackedBranches.get(myRoot);
    String currentBranch = bzrBranchPair.getBranch().getName();
    String remoteBranch = bzrBranchPair.getDest().getName();
    try {
      BzrRepository repository = BzrUtil.getRepositoryManager(myProject).getRepositoryForRoot(myRoot);
      if (repository == null) {
        LOG.error("Repository is null for root " + myRoot);
        return true; // fail safe
      }
      final Collection<String> remotelyChanged = BzrUtil
        .getPathsDiffBetweenRefs(ServiceManager.getService(Bzr.class), repository, currentBranch, remoteBranch);
      final List<File> locallyChanged = myChangeListManager.getAffectedPaths();
      for (File localPath : locallyChanged) {
        if (remotelyChanged.contains(FilePathsHelper.convertPath(localPath.getPath()))) {
         // found a file which was changed locally and remotely => need to save
          return true;
        }
      }
      return false;
    } catch (VcsException e) {
      LOG.info("failed to get remotely changed files for " + currentBranch + ".." + remoteBranch, e);
      return true; // fail safe
    }
  }

  private void cancel() {
    try {
      BzrSimpleHandler h = new BzrSimpleHandler(myProject, myRoot, BzrCommand.RESET);
      h.addParameters("--merge");
      h.run();
    } catch (VcsException e) {
      LOG.info("cancel git reset --merge", e);
      BzrUIUtil.notifyImportantError(myProject, "Couldn't reset merge", e.getLocalizedMessage());
    }
  }

  // parses the output of merge conflict returning files which would be overwritten by merge. These files will be stashed.
  private List<FilePath> getFilesOverwrittenByMerge(@NotNull List<String> mergeOutput) {
    final List<FilePath> paths = new ArrayList<FilePath>();
    for  (String line : mergeOutput) {
      if (StringUtil.isEmptyOrSpaces(line)) {
        continue;
      }
      if (line.contains("Please, commit your changes or stash them before you can merge")) {
        break;
      }
      line = line.trim();

      final String path;
      try {
        path = myRoot.getPath() + "/" + BzrUtil.unescapePath(line);
        final File file = new File(path);
        if (file.exists()) {
          paths.add(new FilePathImpl(file, false));
        }
      } catch (VcsException e) { // just continue
      }
    }
    return paths;
  }

  private Collection<Change> getLocalChangesFilteredByFiles(List<FilePath> paths) {
    final Collection<Change> changes = new HashSet<Change>();
    for(LocalChangeList list : myChangeListManager.getChangeLists()) {
      for (Change change : list.getChanges()) {
        final ContentRevision afterRevision = change.getAfterRevision();
        final ContentRevision beforeRevision = change.getBeforeRevision();
        if ((afterRevision != null && paths.contains(afterRevision.getFile())) || (beforeRevision != null && paths.contains(beforeRevision.getFile()))) {
          changes.add(change);
        }
      }
    }
    return changes;
  }

  @Override
  public String toString() {
    return "Merge updater";
  }

  private enum MergeError {
    CONFLICT,
    LOCAL_CHANGES,
    OTHER
  }

  private static class MergeLineListener extends BzrLineHandlerAdapter {
    private MergeError myMergeError;
    private List<String> myOutput = new ArrayList<String>();
    private boolean myLocalChangesError = false;

    @Override
    public void onLineAvailable(String line, Key outputType) {
      if (myLocalChangesError) {
        myOutput.add(line);
      } else if (line.contains("Automatic merge failed; fix conflicts and then commit the result")) {
        myMergeError = MergeError.CONFLICT;
      } else if (line.contains("Your local changes to the following files would be overwritten by merge")) {
        myMergeError = MergeError.LOCAL_CHANGES;
        myLocalChangesError = true;
      }
    }

    public MergeError getMergeError() {
      return myMergeError;
    }

    public List<String> getOutput() {
      return myOutput;
    }
  }

  private static class MyConflictResolver extends BzrConflictResolver {
    private final BzrMerger myMerger;
    private final VirtualFile myRoot;

    public MyConflictResolver(Project project, @NotNull Bzr bzr, BzrMerger merger, VirtualFile root) {
      super(project, bzr, ServiceManager.getService(BzrPlatformFacade.class), Collections.singleton(root), makeParams());
      myMerger = merger;
      myRoot = root;
    }
    
    private static Params makeParams() {
      Params params = new Params();
      params.setErrorNotificationTitle("Can't complete update");
      params.setMergeDescription("Merge conflicts detected. Resolve them before continuing update.");
      return params;
    }

    @Override protected boolean proceedIfNothingToMerge() throws VcsException {
      myMerger.mergeCommit(myRoot);
      return true;
    }

    @Override protected boolean proceedAfterAllMerged() throws VcsException {
      myMerger.mergeCommit(myRoot);
      return true;
    }
  }
}
