/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package bazaar4idea.vfs;

import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.commands.Bzr;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.util.BzrFileUtils;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.ui.AppUIUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.VcsBackgroundTask;
import com.intellij.vcsUtil.VcsFileUtil;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BzrVFSListener extends VcsVFSListener {
  /**
   * More than zero if events are suppressed
   */
  private final AtomicInteger myEventsSuppressLevel = new AtomicInteger(0);
  private final Bzr myBzr;

  public BzrVFSListener(final Project project, final BzrVcs vcs, Bzr bzr) {
    super(project, vcs);
    myBzr = bzr;
  }

  /**
   * Set events suppressed, the events should be unsuppressed later
   *
   * @param value true if events should be suppressed, false otherwise
   */
  public void setEventsSuppressed(boolean value) {
    if (value) {
      myEventsSuppressLevel.incrementAndGet();
    }
    else {
      int v = myEventsSuppressLevel.decrementAndGet();
      assert v >= 0;
    }
  }

  @Override
  protected boolean isEventIgnored(VirtualFileEvent event, boolean putInDirty) {
    return super.isEventIgnored(event, putInDirty) || myEventsSuppressLevel.get() != 0;
  }

  protected String getAddTitle() {
    return BzrBundle.getString("vfs.listener.add.title");
  }

  protected String getSingleFileAddTitle() {
    return BzrBundle.getString("vfs.listener.add.single.title");
  }

  protected String getSingleFileAddPromptTemplate() {
    return BzrBundle.getString("vfs.listener.add.single.prompt");
  }

  @Override
  protected void executeAdd(final List<VirtualFile> addedFiles, final Map<VirtualFile, VirtualFile> copiedFiles) {
    // Filter added files before further processing
    final Map<VirtualFile, List<VirtualFile>> sortedFiles;
    try {
      sortedFiles = BzrUtil.sortFilesByBzrRoot(addedFiles, true);
    }
    catch (VcsException e) {
      throw new RuntimeException("The exception is not expected here", e);
    }
    final HashSet<VirtualFile> retainedFiles = new HashSet<VirtualFile>();
    final ProgressManager progressManager = ProgressManager.getInstance();
    progressManager.run(new Task.Backgroundable(myProject, BzrBundle.getString("vfs.listener.checking.ignored"), false) {
      @Override
      public void run(@NotNull ProgressIndicator pi) {
        for (Map.Entry<VirtualFile, List<VirtualFile>> e : sortedFiles.entrySet()) {
          VirtualFile root = e.getKey();
          final List<VirtualFile> files = e.getValue();
          pi.setText(root.getPresentableUrl());
          try {
            retainedFiles.addAll(myBzr.untrackedFiles(myProject, root, files));
          }
          catch (final VcsException ex) {
            UIUtil.invokeLaterIfNeeded(new Runnable() {
              public void run() {
                gitVcs().showMessages(ex.getMessage());
              }
            });
          }
        }
        addedFiles.retainAll(retainedFiles);

        AppUIUtil.invokeLaterIfProjectAlive(myProject, new Runnable() {
          @Override
          public void run() {
            originalExecuteAdd(addedFiles, copiedFiles);
          }
        });
      }
    });
  }

  /**
   * The version of execute add before overriding
   *
   * @param addedFiles  the added files
   * @param copiedFiles the copied files
   */
  private void originalExecuteAdd(List<VirtualFile> addedFiles, final Map<VirtualFile, VirtualFile> copiedFiles) {
    super.executeAdd(addedFiles, copiedFiles);
  }

  protected void performAdding(final Collection<VirtualFile> addedFiles, final Map<VirtualFile, VirtualFile> copyFromMap) {
    // copied files (copyFromMap) are ignored, because they are included into added files.
    performAdding(ObjectsConvertor.vf2fp(new ArrayList<VirtualFile>(addedFiles)));
  }

  private BzrVcs gitVcs() {
    return ((BzrVcs)myVcs);
  }

  private void performAdding(Collection<FilePath> filesToAdd) {
    performBackgroundOperation(filesToAdd, BzrBundle.getString("add.adding"), new LongOperationPerRootExecutor() {
      @Override
      public void execute(@NotNull VirtualFile root, @NotNull List<FilePath> files) throws VcsException {
        BzrFileUtils.addPaths(myProject, root, files);
        VcsFileUtil.markFilesDirty(myProject, files);
      }

      @Override
      public Collection<File> getFilesToRefresh() {
        return Collections.emptyList();
      }
    });
  }

  protected String getDeleteTitle() {
    return BzrBundle.getString("vfs.listener.delete.title");
  }

  protected String getSingleFileDeleteTitle() {
    return BzrBundle.getString("vfs.listener.delete.single.title");
  }

  protected String getSingleFileDeletePromptTemplate() {
    return BzrBundle.getString("vfs.listener.delete.single.prompt");
  }

  protected void performDeletion(final List<FilePath> filesToDelete) {
    performBackgroundOperation(filesToDelete, BzrBundle.getString("remove.removing"), new LongOperationPerRootExecutor() {
      HashSet<File> filesToRefresh = new HashSet<File>();

      public void execute(@NotNull VirtualFile root, @NotNull List<FilePath> files) throws VcsException {
        final File rootFile = new File(root.getPath());
        BzrFileUtils.delete(myProject, root, files, "--quiet");
        if (myProject != null && !myProject.isDisposed()) {
          VcsFileUtil.markFilesDirty(myProject, files);
        }
        for (FilePath p : files) {
          for (File f = p.getIOFile(); f != null && !f.equals(rootFile); f = f.getParentFile()) {
            filesToRefresh.add(f);
          }
        }
      }

      public Collection<File> getFilesToRefresh() {
        return filesToRefresh;
      }
    });
  }

  protected void performMoveRename(final List<MovedFileInfo> movedFiles) {
    (new VcsBackgroundTask<MovedFileInfo>(myProject,
                                          BzrBundle.message("move.moving"),
                                          VcsConfiguration.getInstance(myProject).getAddRemoveOption(),
                                          movedFiles) {
      protected void process(final MovedFileInfo file) throws VcsException {
        final FilePath source = VcsUtil.getFilePath(file.myOldPath);
        final FilePath target = VcsUtil.getFilePath(file.myNewPath);
        VirtualFile sourceRoot = VcsUtil.getVcsRootFor(myProject, source);
        VirtualFile targetRoot = VcsUtil.getVcsRootFor(myProject, target);
        if (sourceRoot != null && targetRoot != null) {
          BzrFileUtils.moveFile(myProject, sourceRoot, source, targetRoot, target, "--quiet");
        }
        List<FilePath> filesToRefresh = new ArrayList<FilePath>();
        filesToRefresh.add(source);
        filesToRefresh.add(target);
        VcsFileUtil.markFilesDirty(myProject, filesToRefresh);
      }

    }).queue();
  }

  protected boolean isDirectoryVersioningSupported() {
    return true;
  }

  @Override
  protected Collection<FilePath> selectFilePathsToDelete(final List<FilePath> deletedFiles) {
    // For git asking about vcs delete does not make much sense. The result is practically identical.
    return deletedFiles;
  }

  private void performBackgroundOperation(@NotNull Collection<FilePath> files, @NotNull String operationTitle,
                                          @NotNull final LongOperationPerRootExecutor executor) {
    final Map<VirtualFile, List<FilePath>> sortedFiles;
    try {
      sortedFiles = BzrUtil.sortFilePathsByBzrRoot(files, true);
    }
    catch (VcsException e) {
      gitVcs().showMessages(e.getMessage());
      return;
    }

    BzrVcs.runInBackground(new Task.Backgroundable(myProject, operationTitle) {
      public void run(@NotNull ProgressIndicator indicator) {
        for (Map.Entry<VirtualFile, List<FilePath>> e : sortedFiles.entrySet()) {
          try {
            executor.execute(e.getKey(), e.getValue());
          }
          catch (final VcsException ex) {
            UIUtil.invokeLaterIfNeeded(new Runnable() {
              public void run() {
                gitVcs().showMessages(ex.getMessage());
              }
            });
          }
        }
        LocalFileSystem.getInstance().refreshIoFiles(executor.getFilesToRefresh());
      }
    });
  }

  private interface LongOperationPerRootExecutor {
    void execute(@NotNull VirtualFile root, @NotNull List<FilePath> files) throws VcsException;
    Collection<File> getFilesToRefresh();
  }

}
