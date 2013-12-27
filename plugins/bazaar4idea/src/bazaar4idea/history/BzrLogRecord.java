/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

import bazaar4idea.BzrContentRevision;
import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.commands.BzrHandler;
import bazaar4idea.history.wholeTree.AbstractHash;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static bazaar4idea.history.BzrLogParser.BzrLogOption.*;

/**
 * One record (commit information) returned by git log output.
 * The access methods try heavily to return some default value if real is unavailable, for example, blank string is better than null.
 * BUT if one tries to get an option which was not specified to the BzrLogParser, one will get null.
 * @see BzrLogParser
 */
class BzrLogRecord {

  private final Map<BzrLogParser.BzrLogOption, String> myOptions;
  private final List<String> myPaths;
  private final List<BzrLogStatusInfo> myStatusInfo;
  private final boolean mySupportsRawBody;

  private BzrHandler myHandler;

  BzrLogRecord(@NotNull Map<BzrLogParser.BzrLogOption, String> options,
               @NotNull List<String> paths,
               @NotNull List<BzrLogStatusInfo> statusInfo,
               boolean supportsRawBody) {
    myOptions = options;
    myPaths = paths;
    myStatusInfo = statusInfo;
    mySupportsRawBody = supportsRawBody;
  }

  private List<String> getPaths() {
    return myPaths;
  }

  @NotNull
  List<BzrLogStatusInfo> getStatusInfos() {
    return myStatusInfo;
  }

  @NotNull
  public List<FilePath> getFilePaths(VirtualFile root) throws VcsException {
    List<FilePath> res = new ArrayList<FilePath>();
    String prefix = root.getPath() + "/";
    for (String strPath : getPaths()) {
      final String subPath = BzrUtil.unescapePath(strPath);
      final FilePath revisionPath = VcsUtil.getFilePathForDeletedFile(prefix + subPath, false);
      res.add(revisionPath);
    }
    return res;
  }

  private String lookup(BzrLogParser.BzrLogOption key) {
    return shortBuffer(myOptions.get(key));
  }

  // trivial access methods
  String getHash() { return lookup(HASH); }
  String getAuthorName() { return lookup(AUTHOR_NAME); }
  String getAuthorEmail() { return lookup(AUTHOR_EMAIL); }
  String getCommitterName() { return lookup(COMMITTER_NAME); }
  String getCommitterEmail() { return lookup(COMMITTER_EMAIL); }
  String getSubject() { return lookup(SUBJECT); }
  String getBody() { return lookup(BODY); }
  String getRawBody() { return lookup(RAW_BODY); }
  String getShortenedRefLog() { return lookup(SHORT_REF_LOG_SELECTOR); }

  // access methods with some formatting or conversion

  Date getDate() {
    return BzrUtil.parseTimestampWithNFEReport(myOptions.get(COMMIT_TIME), myHandler, myOptions.toString());
  }

  long getCommitTime() {
    return Long.parseLong(myOptions.get(COMMIT_TIME).trim()) * 1000;
  }

  long getAuthorTimeStamp() {
    return Long.parseLong(myOptions.get(AUTHOR_TIME).trim()) * 1000;
  }

  String getAuthorAndCommitter() {
    String author = String.format("%s <%s>", myOptions.get(AUTHOR_NAME), myOptions.get(AUTHOR_EMAIL));
    String committer = String.format("%s <%s>", myOptions.get(COMMITTER_NAME), myOptions.get(COMMITTER_EMAIL));
    return BzrUtil.adjustAuthorName(author, committer);
  }

  String getFullMessage() {
    return mySupportsRawBody ? getRawBody().trim() : ((getSubject() + "\n\n" + getBody()).trim());
  }

  String[] getParentsHashes() {
    final String parents = lookup(PARENTS);
    if (parents.trim().length() == 0) return ArrayUtil.EMPTY_STRING_ARRAY;
    return parents.split(" ");
  }

  public Collection<String> getRefs() {
    final String decorate = myOptions.get(REF_NAMES);
    final String[] refNames = parseRefNames(decorate);
    final List<String> result = new ArrayList<String>(refNames.length);
    for (String refName : refNames) {
      result.add(shortBuffer(refName));
    }
    return result;
  }
  /**
   * Returns the list of tags and the list of branches.
   * A single method is used to return both, because they are returned together by Bazaar and we don't want to parse them twice.
   * @return
   * @param allBranchesSet
   */
  /*Pair<List<String>, List<String>> getTagsAndBranches(SymbolicRefs refs) {
    final String decorate = myOptions.get(REF_NAMES);
    final String[] refNames = parseRefNames(decorate);
    final List<String> tags = refNames.length > 0 ? new ArrayList<String>() : Collections.<String>emptyList();
    final List<String> branches = refNames.length > 0 ? new ArrayList<String>() : Collections.<String>emptyList();
    for (String refName : refNames) {
      if (refs.contains(refName)) {
        // also some gits can return ref name twice (like (HEAD, HEAD), so check we will show it only once)
        if (!branches.contains(refName)) {
          branches.add(shortBuffer(refName));
        }
      } else {
        if (!tags.contains(refName)) {
          tags.add(shortBuffer(refName));
        }
      }
    }
    return Pair.create(tags, branches);
  }*/

  private static String[] parseRefNames(final String decorate) {
    final int startParentheses = decorate.indexOf("(");
    final int endParentheses = decorate.indexOf(")");
    if ((startParentheses == -1) || (endParentheses == -1)) return ArrayUtil.EMPTY_STRING_ARRAY;
    final String refs = decorate.substring(startParentheses + 1, endParentheses);
    return refs.split(", ");
  }

  private static String shortBuffer(String raw) {
    return new String(raw);
  }

  public List<Change> parseChanges(Project project, VirtualFile vcsRoot) throws VcsException {
    BzrRevisionNumber thisRevision = new BzrRevisionNumber(getHash(), getDate());
    List<BzrRevisionNumber> parentRevisions = prepareParentRevisions();

    List<Change> result = new ArrayList<Change>();
    for (BzrLogStatusInfo statusInfo: myStatusInfo) {
      result.add(parseChange(project, vcsRoot, parentRevisions, statusInfo, thisRevision));
    }
    return result;
  }

  private List<BzrRevisionNumber> prepareParentRevisions() {
    final String[] parentsHashes = getParentsHashes();
    final List<AbstractHash> parents = new ArrayList<AbstractHash>(parentsHashes.length);
    for (String parentsShortHash : parentsHashes) {
      parents.add(AbstractHash.create(parentsShortHash));
    }

    final List<BzrRevisionNumber> parentRevisions = new ArrayList<BzrRevisionNumber>(parents.size());
    for (AbstractHash parent : parents) {
      parentRevisions.add(new BzrRevisionNumber(parent.getString()));
    }
    return parentRevisions;
  }

  private static Change parseChange(final Project project, final VirtualFile vcsRoot, final List<BzrRevisionNumber> parentRevisions,
                                    final BzrLogStatusInfo statusInfo, final VcsRevisionNumber thisRevision) throws VcsException {
    final ContentRevision before;
    final ContentRevision after;
    FileStatus status = null;
    final String path = statusInfo.getFirstPath();
    @Nullable BzrRevisionNumber firstParent = parentRevisions.isEmpty() ? null : parentRevisions.get(0);

    switch (statusInfo.getType()) {
      case ADDED:
        before = null;
        status = FileStatus.ADDED;
        after = BzrContentRevision.createRevision(vcsRoot, path, thisRevision, project, false, false, true);
        break;
      case UNRESOLVED:
        status = FileStatus.MERGED_WITH_CONFLICTS;
      case MODIFIED:
        if (status == null) {
          status = FileStatus.MODIFIED;
        }
        final FilePath filePath = BzrContentRevision.createPath(vcsRoot, path, false, true, true);
        before = BzrContentRevision.createRevision(vcsRoot, path, firstParent, project, false, false, true);
        after = BzrContentRevision.createMultipleParentsRevision(project, filePath, (BzrRevisionNumber)thisRevision, parentRevisions);
        break;
      case DELETED:
        status = FileStatus.DELETED;
        final FilePath filePathDeleted = BzrContentRevision.createPath(vcsRoot, path, true, true, true);
        before = BzrContentRevision.createRevision(filePathDeleted, firstParent, project, null);
        after = null;
        break;
      case COPIED:
      case RENAMED:
        status = FileStatus.MODIFIED;
        String secondPath = statusInfo.getSecondPath();
        final FilePath filePathAfterRename = BzrContentRevision
          .createPath(vcsRoot, secondPath == null ? path : secondPath, false, false, true);
        before = BzrContentRevision.createRevision(vcsRoot, path, firstParent, project, true, true, true);
        after = BzrContentRevision
          .createMultipleParentsRevision(project, filePathAfterRename, (BzrRevisionNumber)thisRevision, parentRevisions);
        break;
      case TYPE_CHANGED:
        status = FileStatus.MODIFIED;
        final FilePath filePath2 = BzrContentRevision.createPath(vcsRoot, path, false, true, true);
        before = BzrContentRevision.createRevision(vcsRoot, path, firstParent, project, false, false, true);
        after = BzrContentRevision.createMultipleParentsRevision(project, filePath2, (BzrRevisionNumber)thisRevision, parentRevisions);
        break;
      default:
        throw new AssertionError("Unknown file status: " + statusInfo);
    }
    return new Change(before, after, status);
  }

  /**
   * for debugging purposes - see {@link bazaar4idea.BzrUtil#parseTimestampWithNFEReport(String, bazaar4idea.commands.BzrHandler, String)}.
   */
  public void setUsedHandler(BzrHandler handler) {
    myHandler = handler;
  }

  @Override
  public String toString() {
    return String.format("BzrLogRecord{myOptions=%s, myPaths=%s, myStatusInfo=%s, mySupportsRawBody=%s, myHandler=%s}",
                         myOptions, myPaths, myStatusInfo, mySupportsRawBody, myHandler);
  }
}
