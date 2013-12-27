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
package bazaar4idea.rebase;

import bazaar4idea.commands.*;
import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.merge.BzrConflictResolver;
import bazaar4idea.update.BzrUpdateResult;
import bazaar4idea.util.BzrUIUtil;
import bazaar4idea.util.StringScanner;
import bazaar4idea.util.UntrackedFilesNotifier;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Kirill Likhodedov
 */
public class BzrRebaser {

  private final Project myProject;
  private BzrVcs myVcs;
  private List<BzrRebaseUtils.CommitInfo> mySkippedCommits;
  private static final Logger LOG = Logger.getInstance(BzrRebaser.class);
  @NotNull private final Bzr myBzr;
  private @Nullable ProgressIndicator myProgressIndicator;

  public BzrRebaser(Project project, @NotNull Bzr bzr, @Nullable ProgressIndicator progressIndicator) {
    myProject = project;
    myBzr = bzr;
    myProgressIndicator = progressIndicator;
    myVcs = BzrVcs.getInstance(project);
    mySkippedCommits = new ArrayList<BzrRebaseUtils.CommitInfo>();
  }

  public void setProgressIndicator(@Nullable ProgressIndicator progressIndicator) {
    myProgressIndicator = progressIndicator;
  }

  public BzrUpdateResult rebase(@NotNull VirtualFile root,
                                @NotNull List<String> parameters,
                                @Nullable final Runnable onCancel,
                                @Nullable BzrLineHandlerListener lineListener) {
    final BzrLineHandler rebaseHandler = createHandler(root);
    rebaseHandler.addParameters(parameters);
    if (lineListener != null) {
      rebaseHandler.addLineListener(lineListener);
    }

    final BzrRebaseProblemDetector rebaseConflictDetector = new BzrRebaseProblemDetector();
    rebaseHandler.addLineListener(rebaseConflictDetector);
    BzrUntrackedFilesOverwrittenByOperationDetector untrackedFilesDetector = new BzrUntrackedFilesOverwrittenByOperationDetector(root);
    rebaseHandler.addLineListener(untrackedFilesDetector);

    String progressTitle = "Rebasing";
    BzrTask rebaseTask = new BzrTask(myProject, rebaseHandler, progressTitle);
    rebaseTask.setProgressIndicator(myProgressIndicator);
    rebaseTask.setProgressAnalyzer(new BzrStandardProgressAnalyzer());
    final AtomicReference<BzrUpdateResult> updateResult = new AtomicReference<BzrUpdateResult>();
    final AtomicBoolean failure = new AtomicBoolean();
    rebaseTask.executeInBackground(true, new BzrTaskResultHandlerAdapter() {
      @Override
      protected void onSuccess() {
        updateResult.set(BzrUpdateResult.SUCCESS);
      }

      @Override
      protected void onCancel() {
        if (onCancel != null) {
          onCancel.run();
        }
        updateResult.set(BzrUpdateResult.CANCEL);
      }

      @Override
      protected void onFailure() {
        failure.set(true);
      }
    });

    if (failure.get()) {
      updateResult.set(handleRebaseFailure(root, rebaseHandler, rebaseConflictDetector, untrackedFilesDetector));
    }
    return updateResult.get();
  }

  protected BzrLineHandler createHandler(VirtualFile root) {
    return new BzrLineHandler(myProject, root, BzrCommand.REBASE);
  }

  public BzrUpdateResult handleRebaseFailure(VirtualFile root, BzrLineHandler pullHandler,
                                             BzrRebaseProblemDetector rebaseConflictDetector,
                                             BzrMessageWithFilesDetector untrackedWouldBeOverwrittenDetector) {
    if (rebaseConflictDetector.isMergeConflict()) {
      LOG.info("handleRebaseFailure merge conflict");
      final boolean allMerged = new MyConflictResolver(myProject, myBzr, root, this).merge();
      return allMerged ? BzrUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS : BzrUpdateResult.INCOMPLETE;
    } else if (untrackedWouldBeOverwrittenDetector.wasMessageDetected()) {
      LOG.info("handleRebaseFailure: untracked files would be overwritten by checkout");
      UntrackedFilesNotifier.notifyUntrackedFilesOverwrittenBy(myProject, ServiceManager.getService(myProject, BzrPlatformFacade.class),
                                                               untrackedWouldBeOverwrittenDetector.getFiles(), "rebase", null);
      return BzrUpdateResult.ERROR;
    } else {
      LOG.info("handleRebaseFailure error " + pullHandler.errors());
      BzrUIUtil.notifyImportantError(myProject, "Rebase error", BzrUIUtil.stringifyErrors(pullHandler.errors()));
      return BzrUpdateResult.ERROR;
    }
  }


  public void abortRebase(@NotNull VirtualFile root) {
    LOG.info("abortRebase " + root);
    final BzrLineHandler rh = new BzrLineHandler(myProject, root, BzrCommand.REBASE);
    rh.addParameters("--abort");
    BzrTask task = new BzrTask(myProject, rh, "Aborting rebase");
    task.setProgressIndicator(myProgressIndicator);
    task.executeAsync(new BzrTaskResultNotificationHandler(myProject, "Rebase aborted", "Abort rebase cancelled", "Error aborting rebase"));
  }

  public boolean continueRebase(@NotNull VirtualFile root) {
    return continueRebase(root, "--continue");
  }

  /**
   * Runs 'git rebase --continue' on several roots consequently.
   * @return true if rebase successfully finished.
   */
  public boolean continueRebase(@NotNull Collection<VirtualFile> rebasingRoots) {
    boolean success = true;
    for (VirtualFile root : rebasingRoots) {
      success &= continueRebase(root);
    }
    return success;
  }

  // start operation may be "--continue" or "--skip" depending on the situation.
  private boolean continueRebase(final @NotNull VirtualFile root, @NotNull String startOperation) {
    LOG.info("continueRebase " + root + " " + startOperation);
    final BzrLineHandler rh = new BzrLineHandler(myProject, root, BzrCommand.REBASE);
    rh.addParameters(startOperation);
    final BzrRebaseProblemDetector rebaseConflictDetector = new BzrRebaseProblemDetector();
    rh.addLineListener(rebaseConflictDetector);

    makeContinueRebaseInteractiveEditor(root, rh);

    final BzrTask rebaseTask = new BzrTask(myProject, rh, "git rebase " + startOperation);
    rebaseTask.setProgressAnalyzer(new BzrStandardProgressAnalyzer());
    rebaseTask.setProgressIndicator(myProgressIndicator);
    return executeRebaseTaskInBackground(root, rh, rebaseConflictDetector, rebaseTask);
  }

  protected void makeContinueRebaseInteractiveEditor(VirtualFile root, BzrLineHandler rh) {
    BzrRebaseEditorService rebaseEditorService = BzrRebaseEditorService.getInstance();
    // TODO If interactive rebase with commit rewording was invoked, this should take the reworded message
    BzrRebaser.TrivialEditor editor = new BzrRebaser.TrivialEditor(rebaseEditorService, myProject, root, rh);
    Integer rebaseEditorNo = editor.getHandlerNo();
    rebaseEditorService.configureHandler(rh, rebaseEditorNo);
  }

  /**
   * @return Roots which have unfinished rebase process. May be empty.
   */
  public @NotNull Collection<VirtualFile> getRebasingRoots() {
    final Collection<VirtualFile> rebasingRoots = new HashSet<VirtualFile>();
    for (VirtualFile root : ProjectLevelVcsManager.getInstance(myProject).getRootsUnderVcs(myVcs)) {
      if (BzrRebaseUtils.isRebaseInTheProgress(root)) {
        rebasingRoots.add(root);
      }
    }
    return rebasingRoots;
  }

  /**
   * Reorders commits so that the given commits go before others, just after the given parentCommit.
   * For example, if A->B->C->D are unpushed commits and B and D are supplied to this method, then after rebase the commits will
   * look like that: B->D->A->C.
   * NB: If there are merges in the unpushed commits being reordered, a conflict would happen. The calling code should probably
   * prohibit reordering merge commits.
   */
  public boolean reoderCommitsIfNeeded(@NotNull final VirtualFile root, @NotNull String parentCommit, @NotNull List<String> olderCommits) throws VcsException {
    List<String> allCommits = new ArrayList<String>(); //TODO
    if (olderCommits.isEmpty() || olderCommits.size() == allCommits.size()) {
      LOG.info("Nothing to reorder. olderCommits: " + olderCommits + " allCommits: " + allCommits);
      return true;
    }

    final BzrLineHandler h = new BzrLineHandler(myProject, root, BzrCommand.REBASE);
    Integer rebaseEditorNo = null;
    BzrRebaseEditorService rebaseEditorService = BzrRebaseEditorService.getInstance();
    try {
      h.addParameters("-i", "-m", "-v");
      h.addParameters(parentCommit);

      final BzrRebaseProblemDetector rebaseConflictDetector = new BzrRebaseProblemDetector();
      h.addLineListener(rebaseConflictDetector);

      final PushRebaseEditor pushRebaseEditor = new PushRebaseEditor(rebaseEditorService, root, olderCommits, false, h);
      rebaseEditorNo = pushRebaseEditor.getHandlerNo();
      rebaseEditorService.configureHandler(h, rebaseEditorNo);

      final BzrTask rebaseTask = new BzrTask(myProject, h, "Reordering commits");
      rebaseTask.setProgressIndicator(myProgressIndicator);
      return executeRebaseTaskInBackground(root, h, rebaseConflictDetector, rebaseTask);
    } finally { // TODO should be unregistered in the task.success
      // unregistering rebase service
      if (rebaseEditorNo != null) {
        rebaseEditorService.unregisterHandler(rebaseEditorNo);
      }
    }
  }

  private boolean executeRebaseTaskInBackground(VirtualFile root, BzrLineHandler h, BzrRebaseProblemDetector rebaseConflictDetector, BzrTask rebaseTask) {
    final AtomicBoolean result = new AtomicBoolean();
    final AtomicBoolean failure = new AtomicBoolean();
    rebaseTask.executeInBackground(true, new BzrTaskResultHandlerAdapter() {
      @Override protected void onSuccess() {
        result.set(true);
      }

      @Override protected void onCancel() {
        result.set(false);
      }

      @Override protected void onFailure() {
        failure.set(true);
      }
    });
    if (failure.get()) {
      result.set(handleRebaseFailure(root, h, rebaseConflictDetector));
    }
    return result.get();
  }

  /**
   * @return true if the failure situation was resolved successfully, false if we failed to resolve the problem.
   */
  private boolean handleRebaseFailure(final VirtualFile root, final BzrLineHandler h, BzrRebaseProblemDetector rebaseConflictDetector) {
    if (rebaseConflictDetector.isMergeConflict()) {
      LOG.info("handleRebaseFailure merge conflict");
      return new BzrConflictResolver(myProject, myBzr, ServiceManager.getService(BzrPlatformFacade.class), Collections.singleton(root), makeParamsForRebaseConflict()) {
        @Override protected boolean proceedIfNothingToMerge() {
          return continueRebase(root, "--continue");
        }

        @Override protected boolean proceedAfterAllMerged() {
          return continueRebase(root, "--continue");
        }
      }.merge();
    }
    else if (rebaseConflictDetector.isNoChangeError()) {
      LOG.info("handleRebaseFailure no changes error detected");
      try {
        if (BzrUtil.hasLocalChanges(true, myProject, root)) {
          LOG.error("The rebase detector incorrectly detected 'no changes' situation. Attempting to continue rebase.");
          return continueRebase(root);
        }
        else if (BzrUtil.hasLocalChanges(false, myProject, root)) {
          LOG.warn("No changes from patch were not added to the index. Adding all changes from tracked files.");
          stageEverything(root);
          return continueRebase(root);
        }
        else {
          BzrRebaseUtils.CommitInfo commit = BzrRebaseUtils.getCurrentRebaseCommit(root);
          LOG.info("no changes confirmed. Skipping commit " + commit);
          mySkippedCommits.add(commit);
          return continueRebase(root, "--skip");
        }
      }
      catch (VcsException e) {
        LOG.info("Failed to work around 'no changes' error.", e);
        String message = "Couldn't proceed with rebase. " + e.getMessage();
        BzrUIUtil.notifyImportantError(myProject, "Error rebasing", message);
        return false;
      }
    }
    else {
      LOG.info("handleRebaseFailure error " + h.errors());
      BzrUIUtil.notifyImportantError(myProject, "Error rebasing", BzrUIUtil.stringifyErrors(h.errors()));
      return false;
    }
  }

  private void stageEverything(@NotNull VirtualFile root) throws VcsException {
    BzrSimpleHandler handler = new BzrSimpleHandler(myProject, root, BzrCommand.ADD);
    handler.setSilent(false);
    handler.addParameters("--update");
    handler.run();
  }

  private static BzrConflictResolver.Params makeParamsForRebaseConflict() {
    return new BzrConflictResolver.Params().
      setReverse(true).
      setErrorNotificationTitle("Can't continue rebase").
      setMergeDescription("Merge conflicts detected. Resolve them before continuing rebase.").
      setErrorNotificationAdditionalDescription("Then you may <b>continue rebase</b>. <br/> " +
                                                "You also may <b>abort rebase</b> to restore the original branch and stop rebasing.");
  }

  private static class MyConflictResolver extends BzrConflictResolver {
    private final BzrRebaser myRebaser;
    private final VirtualFile myRoot;

    public MyConflictResolver(Project project, @NotNull Bzr bzr, VirtualFile root, BzrRebaser rebaser) {
      super(project, bzr, ServiceManager.getService(BzrPlatformFacade.class), Collections.singleton(root), makeParams());
      myRebaser = rebaser;
      myRoot = root;
    }

    private static Params makeParams() {
      Params params = new Params();
      params.setReverse(true);
      params.setMergeDescription("Merge conflicts detected. Resolve them before continuing rebase.");
      params.setErrorNotificationTitle("Can't continue rebase");
      params.setErrorNotificationAdditionalDescription("Then you may <b>continue rebase</b>. <br/> You also may <b>abort rebase</b> to restore the original branch and stop rebasing.");
      return params;
    }

    @Override protected boolean proceedIfNothingToMerge() throws VcsException {
      return myRebaser.continueRebase(myRoot);
    }

    @Override protected boolean proceedAfterAllMerged() throws VcsException {
      return myRebaser.continueRebase(myRoot);
    }
  }

  public static class TrivialEditor extends BzrInteractiveRebaseEditorHandler {
    public TrivialEditor(@NotNull BzrRebaseEditorService service,
                         @NotNull Project project,
                         @NotNull VirtualFile root,
                         @NotNull BzrHandler handler) {
      super(service, project, root, handler);
    }

    @Override
    public int editCommits(String path) {
      return 0;
    }
  }

  @NotNull
  public BzrUpdateResult handleRebaseFailure(@NotNull BzrLineHandler handler, @NotNull VirtualFile root,
                                             @NotNull BzrRebaseProblemDetector rebaseConflictDetector,
                                             @NotNull BzrMessageWithFilesDetector untrackedWouldBeOverwrittenDetector) {
    if (rebaseConflictDetector.isMergeConflict()) {
      LOG.info("handleRebaseFailure merge conflict");
      final boolean allMerged = new BzrRebaser.ConflictResolver(myProject, myBzr, root, this).merge();
      return allMerged ? BzrUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS : BzrUpdateResult.INCOMPLETE;
    } else if (untrackedWouldBeOverwrittenDetector.wasMessageDetected()) {
      LOG.info("handleRebaseFailure: untracked files would be overwritten by checkout");
      UntrackedFilesNotifier.notifyUntrackedFilesOverwrittenBy(myProject, ServiceManager.getService(myProject, BzrPlatformFacade.class),
                                                               untrackedWouldBeOverwrittenDetector.getFiles(), "rebase", null);
      return BzrUpdateResult.ERROR;
    } else {
      LOG.info("handleRebaseFailure error " + handler.errors());
      BzrUIUtil.notifyImportantError(myProject, "Rebase error", BzrUIUtil.stringifyErrors(handler.errors()));
      return BzrUpdateResult.ERROR;
    }
  }

  public static class ConflictResolver extends BzrConflictResolver {
    @NotNull private final BzrRebaser myRebaser;
    @NotNull private final VirtualFile myRoot;

    public ConflictResolver(@NotNull Project project, @NotNull Bzr bzr, @NotNull VirtualFile root, @NotNull BzrRebaser rebaser) {
      super(project, bzr, ServiceManager.getService(BzrPlatformFacade.class), Collections.singleton(root), makeParams());
      myRebaser = rebaser;
      myRoot = root;
    }

    private static Params makeParams() {
      Params params = new Params();
      params.setReverse(true);
      params.setMergeDescription("Merge conflicts detected. Resolve them before continuing rebase.");
      params.setErrorNotificationTitle("Can't continue rebase");
      params.setErrorNotificationAdditionalDescription("Then you may <b>continue rebase</b>. <br/> You also may <b>abort rebase</b> to restore the original branch and stop rebasing.");
      return params;
    }

    @Override protected boolean proceedIfNothingToMerge() throws VcsException {
      return myRebaser.continueRebase(myRoot);
    }

    @Override protected boolean proceedAfterAllMerged() throws VcsException {
      return myRebaser.continueRebase(myRoot);
    }
  }

  /**
   * The rebase editor that just overrides the list of commits
   */
  class PushRebaseEditor extends BzrInteractiveRebaseEditorHandler {
    private final Logger LOG = Logger.getInstance(PushRebaseEditor.class);
    private final List<String> myCommits; // The reordered commits
    private final boolean myHasMerges; // true means that the root has merges

    /**
     * The constructor from fields that is expected to be
     * accessed only from {@link BzrRebaseEditorService}.
     *
     * @param rebaseEditorService
     * @param root      the git repository root
     * @param commits   the reordered commits
     * @param hasMerges if true, the vcs root has merges
     */
    public PushRebaseEditor(BzrRebaseEditorService rebaseEditorService,
                            final VirtualFile root,
                            List<String> commits,
                            boolean hasMerges,
                            BzrHandler h) {
      super(rebaseEditorService, myProject, root, h);
      myCommits = commits;
      myHasMerges = hasMerges;
    }

    public int editCommits(String path) {
      if (!myRebaseEditorShown) {
        myRebaseEditorShown = true;
        if (myHasMerges) {
          return 0;
        }
        try {
          TreeMap<String, String> pickLines = new TreeMap<String, String>();
          StringScanner s = new StringScanner(new String(FileUtil.loadFileText(new File(path), BzrUtil.UTF8_ENCODING)));
          while (s.hasMoreData()) {
            if (!s.tryConsume("pick ")) {
              s.line();
              continue;
            }
            String commit = s.spaceToken();
            pickLines.put(commit, "pick " + commit + " " + s.line());
          }
          PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(path), BzrUtil.UTF8_ENCODING));
          try {
            for (String commit : myCommits) {
              String key = pickLines.headMap(commit + "\u0000").lastKey();
              if (key == null || !commit.startsWith(key)) {
                continue; // commit from merged branch
              }
              w.print(pickLines.get(key) + "\n");
            }
          }
          finally {
            w.close();
          }
          return 0;
        }
        catch (Exception ex) {
          LOG.error("Editor failed: ", ex);
          return 1;
        }
      }
      else {
        return super.editCommits(path);
      }
    }
  }


}
