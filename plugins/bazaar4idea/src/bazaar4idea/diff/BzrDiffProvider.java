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
package bazaar4idea.diff;

import bazaar4idea.BzrFileRevision;
import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.history.BzrHistoryUtils;
import bazaar4idea.i18n.BzrBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.diff.DiffMixin;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.diff.ItemLatestState;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionDescription;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import bazaar4idea.BzrContentRevision;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * Bazaar diff provider
 */
public class BzrDiffProvider implements DiffProvider, DiffMixin {
  /**
   * The context project
   */
  private final Project myProject;
  /**
   * The status manager for the project
   */
  private final FileStatusManager myStatusManager;
  /**
   *
   */
  private static final Set<FileStatus> ourGoodStatuses;

  static {
    ourGoodStatuses = new THashSet<FileStatus>();
    ourGoodStatuses.addAll(
      Arrays.asList(FileStatus.NOT_CHANGED, FileStatus.DELETED, FileStatus.MODIFIED, FileStatus.MERGE, FileStatus.MERGED_WITH_CONFLICTS));
  }

  /**
   * A constructor
   *
   * @param project the context project
   */
  public BzrDiffProvider(@NotNull Project project) {
    myProject = project;
    myStatusManager = FileStatusManager.getInstance(myProject);
  }

  /**
   * {@inheritDoc}
   */
  @Nullable
  public VcsRevisionNumber getCurrentRevision(VirtualFile file) {
    if (file.isDirectory()) {
      return null;
    }
    try {
      return BzrHistoryUtils.getCurrentRevision(myProject, VcsUtil.getFilePath(file.getPath()), "HEAD");
    }
    catch (VcsException e) {
      return null;
    }
  }

  @Nullable
  @Override
  public VcsRevisionDescription getCurrentRevisionDescription(final VirtualFile file) {
    if (file.isDirectory()) {
      return null;
    }
    try {
      return BzrHistoryUtils.getCurrentRevisionDescription(myProject, VcsUtil.getFilePath(file.getPath()), null);
    }
    catch (VcsException e) {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Nullable
  public ItemLatestState getLastRevision(VirtualFile file) {
    if (file.isDirectory()) {
      return null;
    }
    if (!ourGoodStatuses.contains(myStatusManager.getStatus(file))) {
      return null;
    }
    try {
      return BzrHistoryUtils.getLastRevision(myProject, VcsUtil.getFilePath(file.getPath()));
    }
    catch (VcsException e) {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Nullable
  public ContentRevision createFileContent(VcsRevisionNumber revisionNumber, VirtualFile selectedFile) {
    if (selectedFile.isDirectory()) {
      return null;
    }
    final String path = selectedFile.getPath();
    if (BzrUtil.bzrRootOrNull(selectedFile) == null) {
      return null;
    }

    // faster, if there were no renames
    FilePath filePath = VcsUtil.getFilePath(path);
    try {
      final CommittedChangesProvider committedChangesProvider = BzrVcs.getInstance(myProject).getCommittedChangesProvider();
      final Pair<CommittedChangeList, FilePath> pair = committedChangesProvider.getOneList(selectedFile, revisionNumber);
      if (pair != null) {
        return BzrContentRevision.createRevision(pair.getSecond(), revisionNumber, myProject, selectedFile.getCharset());
      }
    }
    catch (VcsException e) {
      BzrVcs.getInstance(myProject).showErrors(Collections.singletonList(e), BzrBundle.message("diff.find.error", path));
    }

    try {

      for (VcsFileRevision f : BzrHistoryUtils.history(myProject, filePath)) {
        BzrFileRevision bzrRevision = (BzrFileRevision)f;
        if (f.getRevisionNumber().equals(revisionNumber)) {
          return BzrContentRevision.createRevision(bzrRevision.getPath(), revisionNumber, myProject, selectedFile.getCharset());
        }
      }
      BzrContentRevision candidate =
        (BzrContentRevision) BzrContentRevision.createRevision(filePath, revisionNumber, myProject, selectedFile.getCharset());
      try {
        candidate.getContent();
        return candidate;
      }
      catch (VcsException e) {
        // file does not exists
      }
    }
    catch (VcsException e) {
      BzrVcs.getInstance(myProject).showErrors(Collections.singletonList(e), BzrBundle.message("diff.find.error", path));
    }
    return null;
  }

  public ItemLatestState getLastRevision(FilePath filePath) {
    if (filePath.isDirectory()) {
      return null;
    }
    final VirtualFile vf = filePath.getVirtualFile();
    if (vf != null) {
      if (! ourGoodStatuses.contains(myStatusManager.getStatus(vf))) {
        return null;
      }
    }
    try {
      return BzrHistoryUtils.getLastRevision(myProject, filePath);
    }
    catch (VcsException e) {
      return null;
    }
  }

  public VcsRevisionNumber getLatestCommittedRevision(VirtualFile vcsRoot) {
    // todo
    return null;
  }
}
