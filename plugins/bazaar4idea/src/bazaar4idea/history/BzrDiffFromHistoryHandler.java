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
package bazaar4idea.history;

import bazaar4idea.BzrFileRevision;
import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.changes.BzrChangeUtils;
import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowser;
import com.intellij.openapi.vcs.history.CurrentRevision;
import com.intellij.openapi.vcs.history.DiffFromHistoryHandler;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsHistoryUtil;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * {@link DiffFromHistoryHandler#showDiffForTwo(FilePath, VcsFileRevision, VcsFileRevision) "Show Diff" for 2 revision} calls the common code.
 * {@link DiffFromHistoryHandler#showDiffForOne(com.intellij.openapi.actionSystem.AnActionEvent, com.intellij.openapi.vcs.FilePath, com.intellij.openapi.vcs.history.VcsFileRevision, com.intellij.openapi.vcs.history.VcsFileRevision) "Show diff" for 1 revision}
 * behaves differently for merge commits: for them it shown a popup displaying the parents of the selected commit. Selecting a parent
 * from the popup shows the difference with this parent.
 * If an ordinary (not merge) revision with 1 parent, it is the same as usual: just compare with the parent;
 *
 * @author Kirill Likhodedov
 */
public class BzrDiffFromHistoryHandler implements DiffFromHistoryHandler {
  
  private static final Logger LOG = Logger.getInstance(BzrDiffFromHistoryHandler.class);

  @NotNull private final Project myProject;
  @NotNull private final Bzr myBzr;
  @NotNull private final BzrRepositoryManager myRepositoryManager;

  public BzrDiffFromHistoryHandler(@NotNull Project project) {
    myProject = project;
    myBzr = ServiceManager.getService(project, Bzr.class);
    myRepositoryManager = BzrUtil.getRepositoryManager(project);
  }

  @Override
  public void showDiffForOne(@NotNull AnActionEvent e, @NotNull FilePath filePath,
                             @NotNull VcsFileRevision previousRevision, @NotNull VcsFileRevision revision) {
    BzrFileRevision rev = (BzrFileRevision)revision;
    Collection<String> parents = rev.getParents();
    if (parents.size() < 2) {
      doShowDiff(filePath, previousRevision, revision, false);
    }
    else { // merge 
      showDiffForMergeCommit(e, filePath, rev, parents);
    }
  }

  @Override
  public void showDiffForTwo(@NotNull FilePath filePath, @NotNull VcsFileRevision revision1, @NotNull VcsFileRevision revision2) {
    doShowDiff(filePath, revision1, revision2, true);
  }

  private void doShowDiff(@NotNull FilePath filePath, @NotNull VcsFileRevision revision1, @NotNull VcsFileRevision revision2,
                          boolean autoSort) {
    if (!filePath.isDirectory()) {
      VcsHistoryUtil.showDifferencesInBackground(myProject, filePath, revision1, revision2, autoSort);
    }
    else if (revision2 instanceof CurrentRevision) {
      BzrFileRevision left = (BzrFileRevision)revision1;
      showDiffForDirectory(filePath, left.getHash(), null);
    }
    else if (revision1.equals(VcsFileRevision.NULL)) {
      BzrFileRevision right = (BzrFileRevision)revision2;
      showDiffForDirectory(filePath, null, right.getHash());
    }
    else {
      BzrFileRevision left = (BzrFileRevision)revision1;
      BzrFileRevision right = (BzrFileRevision)revision2;
      if (autoSort) {
        Pair<VcsFileRevision, VcsFileRevision> pair = VcsHistoryUtil.sortRevisions(revision1, revision2);
        left = (BzrFileRevision)pair.first;
        right = (BzrFileRevision)pair.second;
      }
      showDiffForDirectory(filePath, left.getHash(), right.getHash());
    }
  }

  private void showDiffForDirectory(@NotNull final FilePath path, @Nullable final String hash1, @Nullable final String hash2) {
    BzrRepository repository = getRepository(path);
    calculateDiffInBackground(repository, path, hash1, hash2, new Consumer<List<Change>>() {
      @Override
      public void consume(List<Change> changes) {
        showDirDiffDialog(path, hash1, hash2, changes);
      }
    });
  }

  @NotNull
  private BzrRepository getRepository(@NotNull FilePath path) {
    BzrRepository repository = myRepositoryManager.getRepositoryForFile(path);
    LOG.assertTrue(repository != null, "Repository is null for " + path);
    return repository;
  }

  // hash1 == null => hash2 is the initial commit
  // hash2 == null => comparing hash1 with local
  private void calculateDiffInBackground(@NotNull final BzrRepository repository, @NotNull final FilePath path,
                                         @Nullable  final String hash1, @Nullable final String hash2,
                                         final Consumer<List<Change>> successHandler) {
    new Task.Backgroundable(myProject, "Comparing revisions...") {
      private List<Change> myChanges;
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          if (hash1 != null) {
            // diff
            myChanges = new ArrayList<Change>(
              BzrChangeUtils.getDiff(repository.getProject(), repository.getRoot(), hash1, hash2, Collections.singletonList(path)));
          }
          else {
            // show the initial commit
            myChanges = new ArrayList<Change>(
              BzrChangeUtils.getRevisionChanges(repository.getProject(), repository.getRoot(), hash2, false, true, true).getChanges());
          }
        }
        catch (VcsException e) {
          showError(e, "Error during requesting diff for directory");
        }
      }

      @Override
      public void onSuccess() {
        successHandler.consume(myChanges);
      }
    }.queue();
  }

  private void showDirDiffDialog(@NotNull FilePath path, @Nullable String hash1, @Nullable String hash2, @NotNull List<Change> diff) {
    DialogBuilder dialogBuilder = new DialogBuilder(myProject);
    String title;
    if (hash2 != null) {
      if (hash1 != null) {
        title = String.format("Difference between %s and %s in %s", BzrUtil.getShortHash(hash1), BzrUtil.getShortHash(hash2), path.getName());
      }
      else {
        title = String.format("Initial commit %s in %s", BzrUtil.getShortHash(hash2), path.getName());
      }
    }
    else {
      LOG.assertTrue(hash1 != null, "hash1 and hash2 can't both be null. Path: " + path);
      title = String.format("Difference between %s and local version in %s", BzrUtil.getShortHash(hash1), path.getName());
    }
    dialogBuilder.setTitle(title);
    dialogBuilder.setActionDescriptors(new DialogBuilder.ActionDescriptor[] { new DialogBuilder.CloseDialogAction()});
    final ChangesBrowser changesBrowser = new ChangesBrowser(myProject, null, diff, null, false, true,
                                                             null, ChangesBrowser.MyUseCase.COMMITTED_CHANGES, null);
    changesBrowser.setChangesToDisplay(diff);
    dialogBuilder.setCenterPanel(changesBrowser);
    dialogBuilder.showNotModal();
  }

  private void showDiffForMergeCommit(@NotNull final AnActionEvent event, @NotNull final FilePath filePath,
                                      @NotNull final BzrFileRevision rev, @NotNull final Collection<String> parents) {

    checkIfFileWasTouchedAndFindParentsInBackground(filePath, rev, parents, new Consumer<MergeCommitPreCheckInfo>() {
      @Override
      public void consume(MergeCommitPreCheckInfo info) {
        if (!info.wasFileTouched()) {
          String message = String.format("There were no changes in %s in this merge commit, besides those which were made in both branches",
                                         filePath.getName());
          VcsBalloonProblemNotifier.showOverVersionControlView(BzrDiffFromHistoryHandler.this.myProject, message, MessageType.INFO);
        }
        showPopup(event, rev, filePath, info.getParents());
      }
    });
  }

  private static class MergeCommitPreCheckInfo {
    private final boolean myWasFileTouched;
    private final Collection<BzrFileRevision> myParents;

    private MergeCommitPreCheckInfo(boolean touched, Collection<BzrFileRevision> parents) {
      myWasFileTouched = touched;
      myParents = parents;
    }

    public boolean wasFileTouched() {
      return myWasFileTouched;
    }

    public Collection<BzrFileRevision> getParents() {
      return myParents;
    }
  }

  private void checkIfFileWasTouchedAndFindParentsInBackground(@NotNull final FilePath filePath, @NotNull final BzrFileRevision rev,
                                                               @NotNull final Collection<String> parentHashes,
                                                               @NotNull final Consumer<MergeCommitPreCheckInfo> resultHandler) {
    new Task.Backgroundable(myProject, "Loading changes...", false) {
      private MergeCommitPreCheckInfo myInfo;

      @Override public void run(@NotNull ProgressIndicator indicator) {
        try {
          BzrRepository repository = getRepository(filePath);
          boolean fileTouched = wasFileTouched(repository, rev);
          Collection<BzrFileRevision> parents = findParentRevisions(repository, rev, parentHashes);
          myInfo = new MergeCommitPreCheckInfo(fileTouched, parents);
        }
        catch (VcsException e) {
          String logMessage = "Error happened while executing git show " + rev + ":" + filePath;
          showError(e, logMessage);
        }
      }

      @Override
      public void onSuccess() {
        if (myInfo != null) { // if info == null => an exception happened
          resultHandler.consume(myInfo);
        }
      }
    }.queue();
  }

  @NotNull
  private Collection<BzrFileRevision> findParentRevisions(@NotNull BzrRepository repository, @NotNull BzrFileRevision currentRevision,
                                                          @NotNull Collection<String> parentHashes) throws VcsException {
    // currentRevision is a merge revision.
    // the file could be renamed in one of the branches, i.e. the name in one of the parent revisions may be different from the name
    // in currentRevision. It can be different even in both parents, but it would a rename-rename conflict, and we don't handle such anyway.

    Collection<BzrFileRevision> parents = new ArrayList<BzrFileRevision>(parentHashes.size());
    for (String parentHash : parentHashes) {
      parents.add(createParentRevision(repository, currentRevision, parentHash));
    }
    return parents;
  }

  @NotNull
  private BzrFileRevision createParentRevision(@NotNull BzrRepository repository, @NotNull BzrFileRevision currentRevision,
                                               @NotNull String parentHash) throws VcsException {
    FilePath currentRevisionPath = currentRevision.getPath();
    if (currentRevisionPath.isDirectory()) {
      // for directories the history doesn't follow renames
      return makeRevisionFromHash(currentRevisionPath, parentHash);
    }

    // can't limit by the path: in that case rename information will be missed
    Collection<Change> changes = BzrChangeUtils.getDiff(myProject, repository.getRoot(), parentHash, currentRevision.getHash(), null);
    for (Change change : changes) {
      ContentRevision afterRevision = change.getAfterRevision();
      ContentRevision beforeRevision = change.getBeforeRevision();
      if (afterRevision != null && afterRevision.getFile().equals(currentRevisionPath)) {
        // if the file was renamed, taking the path how it was in the parent; otherwise the path didn't change
        FilePath path = (beforeRevision != null ? beforeRevision.getFile() : afterRevision.getFile());
        return new BzrFileRevision(myProject, path, new BzrRevisionNumber(parentHash));
      }
    }
    LOG.error(String.format("Could not find parent revision. Will use the path from parent revision. Current revision: %s, parent hash: %s",
                            currentRevision, parentHash));
    return makeRevisionFromHash(currentRevisionPath, parentHash);
  }


  private void showError(VcsException e, String logMessage) {
    LOG.info(logMessage, e);
    VcsBalloonProblemNotifier.showOverVersionControlView(this.myProject, e.getMessage(), MessageType.ERROR);
  }

  private void showPopup(@NotNull AnActionEvent event, @NotNull BzrFileRevision rev, @NotNull FilePath filePath,
                         @NotNull Collection<BzrFileRevision> parents) {
    ActionGroup parentActions = createActionGroup(rev, filePath, parents);
    DataContext dataContext = SimpleDataContext.getProjectContext(myProject);
    ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup("Choose parent to compare", parentActions, dataContext,
                                                                          JBPopupFactory.ActionSelectionAid.NUMBERING, true);
    showPopupInBestPosition(popup, event, dataContext);
  }

  private static void showPopupInBestPosition(@NotNull ListPopup popup, @NotNull AnActionEvent event, @NotNull DataContext dataContext) {
    if (event.getInputEvent() instanceof MouseEvent) {
      if (!event.getPlace().equals(ActionPlaces.UPDATE_POPUP)) {
        popup.show(new RelativePoint((MouseEvent)event.getInputEvent()));
      }
      else { // quick fix for invoking from the context menu: coordinates are calculated incorrectly there.
        popup.showInBestPositionFor(dataContext);
      }
    }
    else {
      popup.showInBestPositionFor(dataContext);
    }
  }

  @NotNull
  private ActionGroup createActionGroup(@NotNull BzrFileRevision rev, @NotNull FilePath filePath, @NotNull Collection<BzrFileRevision> parents) {
    Collection<AnAction> actions = new ArrayList<AnAction>(2);
    for (BzrFileRevision parent : parents) {
      actions.add(createParentAction(rev, filePath, parent));
    }
    return new DefaultActionGroup(ArrayUtil.toObjectArray(actions, AnAction.class));
  }

  @NotNull
  private AnAction createParentAction(@NotNull BzrFileRevision rev, @NotNull FilePath filePath, @NotNull BzrFileRevision parent) {
    return new ShowDiffWithParentAction(filePath, rev, parent);
  }

  @NotNull
  private BzrFileRevision makeRevisionFromHash(@NotNull FilePath filePath, @NotNull String hash) {
    return new BzrFileRevision(myProject, filePath, new BzrRevisionNumber(hash));
  }

  private boolean wasFileTouched(@NotNull BzrRepository repository, @NotNull BzrFileRevision rev) throws VcsException {
    BzrCommandResult result = myBzr.show(repository, rev.getHash());
    if (result.success()) {
      return isFilePresentInOutput(repository, rev.getPath(), result.getOutput());
    }
    throw new VcsException(result.getErrorOutputAsJoinedString());
  }

  private static boolean isFilePresentInOutput(@NotNull BzrRepository repository, @NotNull FilePath path, @NotNull List<String> output) {
    String relativePath = getRelativePath(repository, path);
    for (String line : output) {
      if (line.startsWith("---") || line.startsWith("+++")) {
        if (line.contains(relativePath)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  private static String getRelativePath(@NotNull BzrRepository repository, @NotNull FilePath path) {
    return FileUtil.getRelativePath(repository.getRoot().getPath(), path.getPath(), '/');
  }

  private class ShowDiffWithParentAction extends AnAction {

    @NotNull private final FilePath myFilePath;
    @NotNull private final BzrFileRevision myRevision;
    @NotNull private final BzrFileRevision myParentRevision;

    public ShowDiffWithParentAction(@NotNull FilePath filePath, @NotNull BzrFileRevision rev, @NotNull BzrFileRevision parent) {
      super(BzrUtil.getShortHash(parent.getHash()));
      myFilePath = filePath;
      myRevision = rev;
      myParentRevision = parent;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      doShowDiff(myFilePath, myParentRevision, myRevision, false);
    }

  }
}
