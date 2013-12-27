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
package bazaar4idea.stash;

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrVcs;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.commands.BzrHandlerUtil;
import bazaar4idea.commands.BzrSimpleEventDetector;
import bazaar4idea.merge.BzrConflictResolver;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.ui.BzrUnstashDialog;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.impl.LocalChangesUnderRoots;
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.continuation.ContinuationContext;
import bazaar4idea.commands.Bzr;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.util.*;

/**
 * @author Kirill Likhodedov
 */
public class BzrStashChangesSaver extends BzrChangesSaver {

  private static final Logger LOG = Logger.getInstance(BzrStashChangesSaver.class);
  private final Set<VirtualFile> myStashedRoots = new HashSet<VirtualFile>(); // save stashed roots to unstash only them
  @NotNull private final BzrRepositoryManager myRepositoryManager;

  public BzrStashChangesSaver(@NotNull Project project,
                              @NotNull BzrPlatformFacade platformFacade,
                              @NotNull Bzr bzr,
                              @NotNull ProgressIndicator progressIndicator,
                              @NotNull String stashMessage) {
    super(project, platformFacade, bzr, progressIndicator, stashMessage);
    myRepositoryManager = platformFacade.getRepositoryManager(project);
  }

  @Override
  protected void save(Collection<VirtualFile> rootsToSave) throws VcsException {
    LOG.info("save " + rootsToSave);
    final Map<VirtualFile, Collection<Change>> changes =
      new LocalChangesUnderRoots(myChangeManager, myPlatformFacade.getVcsManager(myProject)).getChangesUnderRoots(rootsToSave);
    stash(changes.keySet());
  }

  @Override
  protected void load(@NotNull ContinuationContext context) {
    try {
      load();
    }
    catch (VcsException e) {
      context.handleException(e, false);
    }
  }

  public void load() throws VcsException {
    for (VirtualFile root : myStashedRoots) {
      loadRoot(root);
    }

    boolean conflictsResolved = new UnstashConflictResolver(myProject, myPlatformFacade, myBzr, myStashedRoots, myParams).merge();
    LOG.info("load: conflicts resolved status is " + conflictsResolved + " in roots " + myStashedRoots);
  }

  @Override
  protected boolean wereChangesSaved() {
    return !myStashedRoots.isEmpty();
  }

  @Override
  public String getSaverName() {
    return "stash";
  }

  @Override
  protected void showSavedChanges() {
    BzrUnstashDialog.showUnstashDialog(myProject, new ArrayList<VirtualFile>(myStashedRoots), myStashedRoots.iterator().next());
  }

  @Override
  public void refresh() {
    // we'll refresh more but this way we needn't compute what files under roots etc
    LocalFileSystem.getInstance().refreshIoFiles(myChangeManager.getAffectedPaths());
  }

  private void stash(Collection<VirtualFile> roots) throws VcsException {
    for (VirtualFile root : roots) {
      final String message = BzrHandlerUtil.formatOperationName("Stashing changes from", root);
      LOG.info(message);
      final String oldProgressTitle = myProgressIndicator.getText();
      myProgressIndicator.setText(message);
      BzrRepository repository = myRepositoryManager.getRepositoryForRoot(root);
      if (repository == null) {
        LOG.error("Repository is null for root " + root);
      }
      else if (BzrStashUtils.saveStash(myBzr, repository, myStashMessage)) {
          myStashedRoots.add(root);
      }
      myProgressIndicator.setText(oldProgressTitle);
    }
  }

  /**
   * Returns true if the root was loaded with conflict.
   * False is returned in all other cases: in the case of success and in case of some other error.
   */
  private boolean loadRoot(final VirtualFile root) throws VcsException {
    LOG.info("loadRoot " + root);
    myProgressIndicator.setText(BzrHandlerUtil.formatOperationName("Unstashing changes to", root));

    BzrRepository repository = myRepositoryManager.getRepositoryForRoot(root);
    if (repository == null) {
      LOG.error("Repository is null for root " + root);
      return false;
    }

    BzrSimpleEventDetector conflictDetector = new BzrSimpleEventDetector(BzrSimpleEventDetector.Event.MERGE_CONFLICT_ON_UNSTASH);
    BzrCommandResult result = myBzr.stashPop(repository, conflictDetector);
    if (result.success()) {
      return false;
    }
    else if (conflictDetector.hasHappened()) {
      return true;
    }
    else {
      LOG.info("unstash failed " + result.getErrorOutputAsJoinedString());
      BzrUIUtil.notifyImportantError(myProject, "Couldn't unstash", "<br/>" + result.getErrorOutputAsHtmlString());
      return false;
    }
  }

  private static class UnstashConflictResolver extends BzrConflictResolver {

    private final Set<VirtualFile> myStashedRoots;

    public UnstashConflictResolver(@NotNull Project project, BzrPlatformFacade platformFacade, @NotNull Bzr bzr,
                                   @NotNull Set<VirtualFile> stashedRoots, @Nullable Params params) {
      super(project, bzr, platformFacade, stashedRoots, makeParamsOrUse(params));
      myStashedRoots = stashedRoots;
    }

    private static Params makeParamsOrUse(@Nullable Params givenParams) {
      if (givenParams != null) {
        return givenParams;
      }
      Params params = new Params();
      params.setErrorNotificationTitle("Local changes were not restored");
      params.setMergeDialogCustomizer(new UnstashMergeDialogCustomizer());
      params.setReverse(true);
      return params;
    }


    @Override
    protected void notifyUnresolvedRemain() {
      BzrVcs.IMPORTANT_ERROR_NOTIFICATION.createNotification("Local changes were restored with conflicts",
                                                "Your uncommitted changes were saved to <a href='saver'>stash</a>.<br/>" +
                                                "Unstash is not complete, you have unresolved merges in your working tree<br/>" +
                                                "<a href='resolve'>Resolve</a> conflicts and drop the stash.",
                                                NotificationType.WARNING, new NotificationListener() {
          @Override
          public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
              if (event.getDescription().equals("saver")) {
                // we don't use #showSavedChanges to specify unmerged root first
                BzrUnstashDialog.showUnstashDialog(myProject, new ArrayList<VirtualFile>(myStashedRoots), myStashedRoots.iterator().next());
              } else if (event.getDescription().equals("resolve")) {
                mergeNoProceed();
              }
            }
          }
      }).notify(myProject);
    }

  }

  private static class UnstashMergeDialogCustomizer extends MergeDialogCustomizer {

    @Override
    public String getMultipleFileMergeDescription(Collection<VirtualFile> files) {
      return "Uncommitted changes that were stashed before update have conflicts with updated files.";
    }

    @Override
    public String getLeftPanelTitle(VirtualFile file) {
      return getConflictLeftPanelTitle();
    }

    @Override
    public String getRightPanelTitle(VirtualFile file, VcsRevisionNumber lastRevisionNumber) {
      return getConflictRightPanelTitle();
    }
  }
}
