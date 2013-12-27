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
package bazaar4idea.status;

import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.changes.BzrChangeUtils;
import bazaar4idea.commands.BzrCommand;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScope;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.BzrContentRevision;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.util.StringScanner;
import org.jetbrains.annotations.NotNull;

import java.util.*;

class BzrOldChangesCollector extends BzrChangesCollector {

  private final List<VirtualFile> myUnversioned = new ArrayList<VirtualFile>(); // Unversioned files
  private final Set<String> myUnmergedNames = new HashSet<String>(); // Names of unmerged files
  private final List<Change> myChanges = new ArrayList<Change>(); // all changes

  /**
   * Collects the changes from git command line and returns the instance of BzrNewChangesCollector from which these changes can be retrieved.
   * This may be lengthy.
   */
  @NotNull
  static BzrOldChangesCollector collect(@NotNull Project project, @NotNull ChangeListManager changeListManager,
                                        @NotNull ProjectLevelVcsManager vcsManager, @NotNull AbstractVcs vcs,
                                        @NotNull VcsDirtyScope dirtyScope, @NotNull VirtualFile vcsRoot) throws VcsException {
    return new BzrOldChangesCollector(project, changeListManager, vcsManager, vcs, dirtyScope, vcsRoot);
  }

  @NotNull
  @Override
  Collection<VirtualFile> getUnversionedFiles() {
    return myUnversioned;
  }

  @NotNull
  @Override
  Collection<Change> getChanges(){
    return myChanges;
  }

  private BzrOldChangesCollector(@NotNull Project project,
                                 @NotNull ChangeListManager changeListManager,
                                 @NotNull ProjectLevelVcsManager vcsManager,
                                 @NotNull AbstractVcs vcs,
                                 @NotNull VcsDirtyScope dirtyScope,
                                 @NotNull VirtualFile vcsRoot) throws VcsException {
    super(project, changeListManager, vcsManager, vcs, dirtyScope, vcsRoot);
    updateIndex();
    collectUnmergedAndUnversioned();
    collectDiffChanges();
  }

  private void updateIndex() throws VcsException {
    BzrSimpleHandler handler = new BzrSimpleHandler(myProject, myVcsRoot, BzrCommand.UPDATE_INDEX);
    handler.addParameters("--refresh", "--ignore-missing");
    handler.setSilent(true);
    handler.setStdoutSuppressed(true);
    handler.ignoreErrorCode(1);
    handler.run();
  }

  /**
   * Collect diff with head
   *
   * @throws VcsException if there is a problem with running git
   */
  private void collectDiffChanges() throws VcsException {
    Collection<FilePath> dirtyPaths = dirtyPaths(true);
    if (dirtyPaths.isEmpty()) {
      return;
    }
    try {
      String output = BzrChangeUtils.getDiffOutput(myProject, myVcsRoot, "HEAD", dirtyPaths);
      BzrChangeUtils
        .parseChanges(myProject, myVcsRoot, null, BzrChangeUtils.resolveReference(myProject, myVcsRoot, "HEAD"), output, myChanges,
                      myUnmergedNames);
    }
    catch (VcsException ex) {
      if (!BzrChangeUtils.isHeadMissing(ex)) {
        throw ex;
      }
      BzrSimpleHandler handler = new BzrSimpleHandler(myProject, myVcsRoot, BzrCommand.LS_FILES);
      handler.addParameters("--cached");
      handler.setSilent(true);
      handler.setStdoutSuppressed(true);
      // During init diff does not works because HEAD
      // will appear only after the first commit.
      // In that case added files are cached in index.
      String output = handler.run();
      if (output.length() > 0) {
        StringTokenizer tokenizer = new StringTokenizer(output, "\n\r");
        while (tokenizer.hasMoreTokens()) {
          final String s = tokenizer.nextToken();
          Change ch = new Change(null, BzrContentRevision.createRevision(myVcsRoot, s, null, myProject, false, false, true), FileStatus.ADDED);
          myChanges.add(ch);
        }
      }
    }
  }

  /**
   * Collect unversioned and unmerged files
   *
   * @throws VcsException if there is a problem with running git
   */
  private void collectUnmergedAndUnversioned() throws VcsException {
    Collection<FilePath> dirtyPaths = dirtyPaths(false);
    if (dirtyPaths.isEmpty()) {
      return;
    }
    // prepare handler
    BzrSimpleHandler handler = new BzrSimpleHandler(myProject, myVcsRoot, BzrCommand.LS_FILES);
    handler.addParameters("-v", "--unmerged");
    handler.setSilent(true);
    handler.setStdoutSuppressed(true);
    // run handler and collect changes
    parseFiles(handler.run());
    // prepare handler
    handler = new BzrSimpleHandler(myProject, myVcsRoot, BzrCommand.LS_FILES);
    handler.addParameters("-v", "--others", "--exclude-standard");
    handler.setSilent(true);
    handler.setStdoutSuppressed(true);
    handler.endOptions();
    handler.addRelativePaths(dirtyPaths);
    if(handler.isLargeCommandLine()) {
      handler = new BzrSimpleHandler(myProject, myVcsRoot, BzrCommand.LS_FILES);
      handler.addParameters("-v", "--others", "--exclude-standard");
      handler.setSilent(true);
      handler.setStdoutSuppressed(true);
      handler.endOptions();
    }
    // run handler and collect changes
    parseFiles(handler.run());
  }

  private void parseFiles(String list) throws VcsException {
    for (StringScanner sc = new StringScanner(list); sc.hasMoreData();) {
      if (sc.isEol()) {
        sc.nextLine();
        continue;
      }
      char status = sc.peek();
      sc.skipChars(2);
      if ('?' == status) {
        VirtualFile file = myVcsRoot.findFileByRelativePath(BzrUtil.unescapePath(sc.line()));
        if (Comparing.equal(BzrUtil.bzrRootOrNull(file), myVcsRoot)) {
          myUnversioned.add(file);
        }
      }
      else { //noinspection HardCodedStringLiteral
        if ('M' == status) {
          sc.boundedToken('\t');
          String file = BzrUtil.unescapePath(sc.line());
          VirtualFile vFile = myVcsRoot.findFileByRelativePath(file);
          if (!Comparing.equal(BzrUtil.bzrRootOrNull(vFile), myVcsRoot)) {
            continue;
          }
          if (!myUnmergedNames.add(file)) {
            continue;
          }
          // assume modify-modify conflict
          ContentRevision before = BzrContentRevision
            .createRevision(myVcsRoot, file, new BzrRevisionNumber("orig_head"), myProject, false, true, true);
          ContentRevision after = BzrContentRevision.createRevision(myVcsRoot, file, null, myProject, false, false, true);
          myChanges.add(new Change(before, after, FileStatus.MERGED_WITH_CONFLICTS));
        }
        else {
          throw new VcsException("Unsupported type of the merge conflict detected: " + status);
        }
      }
    }
  }
}
