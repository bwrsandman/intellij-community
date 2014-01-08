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
package bazaar4idea.status;

import bazaar4idea.BzrContentRevision;
import bazaar4idea.BzrFormatException;
import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.changes.BzrChangeUtils;
import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrHandler;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScope;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.repo.BzrUntrackedFilesHolder;
import org.jetbrains.annotations.NotNull;

import java.util.*;

class BzrNewChangesCollector extends BzrChangesCollector {

  private static final Logger LOG = Logger.getInstance(BzrNewChangesCollector.class);
  private final BzrRepository myRepository;
  private final Collection<Change> myChanges = new HashSet<Change>();
  private final Set<VirtualFile> myUnversionedFiles = new HashSet<VirtualFile>();
  @NotNull private final Bzr myBzr;

  /**
   * Collects the changes from git command line and returns the instance of BzrNewChangesCollector from which these changes can be retrieved.
   * This may be lengthy.
   */
  @NotNull
  static BzrNewChangesCollector collect(@NotNull Project project, @NotNull Bzr bzr, @NotNull ChangeListManager changeListManager,
                                        @NotNull ProjectLevelVcsManager vcsManager, @NotNull AbstractVcs vcs,
                                        @NotNull VcsDirtyScope dirtyScope, @NotNull VirtualFile vcsRoot) throws VcsException {
    return new BzrNewChangesCollector(project, bzr, changeListManager, vcsManager, vcs, dirtyScope, vcsRoot);
  }

  @Override
  @NotNull
  Collection<VirtualFile> getUnversionedFiles() {
    return myUnversionedFiles;
  }

  @NotNull
  @Override
  Collection<Change> getChanges() {
    return myChanges;
  }

  private BzrNewChangesCollector(@NotNull Project project,
                                 @NotNull Bzr bzr,
                                 @NotNull ChangeListManager changeListManager,
                                 @NotNull ProjectLevelVcsManager vcsManager,
                                 @NotNull AbstractVcs vcs,
                                 @NotNull VcsDirtyScope dirtyScope,
                                 @NotNull VirtualFile vcsRoot) throws VcsException
  {
    super(project, changeListManager, vcsManager, vcs, dirtyScope, vcsRoot);
    myBzr = bzr;
    myRepository = BzrUtil.getRepositoryManager(myProject).getRepositoryForRoot(vcsRoot);

    Collection<FilePath> dirtyPaths = dirtyPaths(true);
    if (!dirtyPaths.isEmpty()) {
      collectChanges(dirtyPaths);
      collectUnversionedFiles();
    }
  }

  // calls 'git status' and parses the output, feeding myChanges.
  private void collectChanges(Collection<FilePath> dirtyPaths) throws VcsException {
    BzrSimpleHandler handler = statusHandler(dirtyPaths);
    String output = handler.run();
    parseOutput(output, handler);
  }

  private void collectUnversionedFiles() throws VcsException {
    if (myRepository == null) {
      // if BzrRepository was not initialized at the time of creation of the BzrNewChangesCollector => collecting unversioned files by hands.
      myUnversionedFiles.addAll(myBzr.untrackedFiles(myProject, myVcsRoot, null));
    } else {
      BzrUntrackedFilesHolder untrackedFilesHolder = myRepository.getUntrackedFilesHolder();
      myUnversionedFiles.addAll(untrackedFilesHolder.retrieveUntrackedFiles());
    }
  }

  private BzrSimpleHandler statusHandler(Collection<FilePath> dirtyPaths) {
    BzrSimpleHandler handler = new BzrSimpleHandler(myProject, myVcsRoot, BzrCommand.STATUS);
    final String[] params = {"--short", "--versioned"};   // untracked files are stored separately
    handler.addParameters(params);
    //handler.setSilent(true);
    //handler.setStdoutSuppressed(true);
    handler.endOptions();
    handler.addRelativePaths(dirtyPaths);
    if (handler.isLargeCommandLine()) {
      // if there are too much files, just get all changes for the project
      handler = new BzrSimpleHandler(myProject, myVcsRoot, BzrCommand.STATUS);
      handler.addParameters(params);
      handler.setSilent(true);
      handler.setStdoutSuppressed(true);
      handler.endOptions();
    }
    return handler;
  }

  // handler is here for debugging purposes in the case of parse error
  /*
      Status flags are used to summarise changes to the working tree in a concise
    manner.  They are in the form:

       xxx   <filename>

    where the columns' meanings are as follows.

    Column 1 - versioning/renames:

      + File versioned
      - File unversioned
      R File renamed
      ? File unknown
      X File nonexistent (and unknown to bzr)
      C File has conflicts
      P Entry for a pending merge (not a file)

    Column 2 - contents:

      N File created
      D File deleted
      K File kind changed
      M File modified

    Column 3 - execute:

      * The execute bit was changed

   */
  private void parseOutput(@NotNull String output, @NotNull BzrHandler handler) throws VcsException {
    VcsRevisionNumber head = getHead();

    List<String> split = Arrays.asList(StringUtil.splitByLinesDontTrim(output));

    for (String line: split) {
      if (StringUtil.isEmptyOrSpaces(line)) { // skip empty lines if any (e.g. the whole output may be empty on a clean working tree).
        continue;
      }

      // format: XY_filename where _ stands for space.
      if (line.length() < 5) { // X, Y, space and at least one symbol for the file
        throwGFE("Line is too short.", handler, output, line, '0', '0');
      }
      final String xyzStatus = line.substring(0, 3);
      final String filepath = line.substring(4); // skipping the space
      final char xStatus = xyzStatus.charAt(0);
      final char yStatus = xyzStatus.charAt(1);
      final char zStatus = xyzStatus.charAt(2);

      switch (xStatus) {
        case ' ':
          if (yStatus == 'M') {
            reportModified(filepath, head);
          } else if (yStatus == 'D') {
            reportDeleted(filepath, head);
          } else if (yStatus == 'K') {
            reportKindChanged(filepath, head);
          } else if (yStatus == 'N') {
            reportAdded(filepath);
          } else {
            throwYStatus(output, handler, line, xStatus, yStatus);
          }
          break;

        case '+':
          if (yStatus == 'N') {
            reportAdded(filepath);
          } else if (yStatus == '!') {
            throwYStatus(output, handler, line, xStatus, yStatus);
          }  else {
            throwYStatus(output, handler, line, xStatus, yStatus);
          }
          break;

        case '-':
          if (yStatus == 'N') {
            reportAdded(filepath);
          }  else {
            throwYStatus(output, handler, line, xStatus, yStatus);
          }
          break;

        case 'R':
          if (yStatus == ' ') {
            reportRename(StringUtil.split(filepath, " => ").get(1), StringUtil.split(filepath, " => ").get(0), head);
          } else if (yStatus == 'D') {
            reportDeleted(StringUtil.split(filepath, " => ").get(0), head);
          } else {
            throwYStatus(output, handler, line, xStatus, yStatus);
          }
          break;

        case 'C':
          reportConflict(filepath, head);
          break;

        case '?':
        case 'X':
          throwGFE("Unexpected unversioned file flag.", handler, output, line, xStatus, yStatus);
          break;

        default:
          throwGFE("Unexpected symbol as xStatus.", handler, output, line, xStatus, yStatus);

      }
    }
  }

  @NotNull
  private VcsRevisionNumber getHead() throws VcsException {
    if (myRepository != null) {
      // we force update the BzrRepository, because update is asynchronous, and thus the BzrChangeProvider may be asked for changes
      // before the BzrRepositoryUpdater has captures the current revision change and has updated the BzrRepository.
      myRepository.update();
      final String rev = myRepository.getCurrentRevision();
      return rev != null ? new BzrRevisionNumber(rev) : VcsRevisionNumber.NULL;
    } else {
      // this may happen on the project startup, when BzrChangeProvider may be queried before BzrRepository has been initialized.
      LOG.info("BzrRepository is null for root " + myVcsRoot);
      return getHeadFromBzr();
    }
  }

  @NotNull
  private VcsRevisionNumber getHeadFromBzr() throws VcsException {
    VcsRevisionNumber nativeHead = VcsRevisionNumber.NULL;
    try {
      nativeHead = BzrChangeUtils.resolveReference(myProject, myVcsRoot, "HEAD");
    }
    catch (VcsException e) {
      if (!BzrChangeUtils.isHeadMissing(e)) { // fresh repository
        throw e;
      }
    }
    return nativeHead;
  }

  private static void throwYStatus(String output, BzrHandler handler, String line, char xStatus, char yStatus) {
    throwGFE("Unexpected symbol as yStatus.", handler, output, line, xStatus, yStatus);
  }

  private static void throwGFE(String message, BzrHandler handler, String output, String line, char xStatus, char yStatus) {
    throw new BzrFormatException(String.format("%s\n xStatus=[%s], yStatus=[%s], line=[%s], \n" +
                                               "handler:\n%s\n output: \n%s",
                                               message, xStatus, yStatus, line.replace('\u0000', '!'), handler, output));
  }

  private void reportModified(String filepath, VcsRevisionNumber head) throws VcsException {
    ContentRevision before = BzrContentRevision.createRevision(myVcsRoot, filepath, head, myProject, false, true, false);
    ContentRevision after = BzrContentRevision.createRevision(myVcsRoot, filepath, null, myProject, false, false, false);
    reportChange(FileStatus.MODIFIED, before, after);
  }

  private void reportKindChanged(String filepath, VcsRevisionNumber head) throws VcsException {
    ContentRevision before = BzrContentRevision.createRevision(myVcsRoot, filepath, head, myProject, false, true, false);
    ContentRevision after = BzrContentRevision.createRevisionForTypeChange(myProject, myVcsRoot, filepath, null, false);
    reportChange(FileStatus.MODIFIED, before, after);
  }

  private void reportAdded(String filepath) throws VcsException {
    ContentRevision before = null;
    ContentRevision after = BzrContentRevision.createRevision(myVcsRoot, filepath, null, myProject, false, false, false);
    reportChange(FileStatus.ADDED, before, after);
  }

  private void reportDeleted(String filepath, VcsRevisionNumber head) throws VcsException {
    ContentRevision before = BzrContentRevision.createRevision(myVcsRoot, filepath, head, myProject, true, true, false);
    ContentRevision after = null;
    reportChange(FileStatus.DELETED, before, after);
  }

  private void reportRename(String filepath, String oldFilename, VcsRevisionNumber head) throws VcsException {
    ContentRevision before = BzrContentRevision.createRevision(myVcsRoot, oldFilename, head, myProject, true, true, false);
    ContentRevision after = BzrContentRevision.createRevision(myVcsRoot, filepath, null, myProject, false, false, false);
    reportChange(FileStatus.MODIFIED, before, after);
  }

  private void reportConflict(String filepath, VcsRevisionNumber head) throws VcsException {
    ContentRevision before = BzrContentRevision.createRevision(myVcsRoot, filepath, head, myProject, false, true, false);
    ContentRevision after = BzrContentRevision.createRevision(myVcsRoot, filepath, null, myProject, false, false, false);
    reportChange(FileStatus.MERGED_WITH_CONFLICTS, before, after);
  }

  private void reportChange(FileStatus status, ContentRevision before, ContentRevision after) {
    myChanges.add(new Change(before, after, status));
  }

}
