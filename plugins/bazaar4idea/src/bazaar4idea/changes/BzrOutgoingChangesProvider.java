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
package bazaar4idea.changes;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrBranchesSearcher;
import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.history.BzrHistoryUtils;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.containers.Convertor;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.history.browser.SHAHash;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class BzrOutgoingChangesProvider implements VcsOutgoingChangesProvider<CommittedChangeList> {
  private final static Logger LOG = Logger.getInstance(BzrOutgoingChangesProvider.class);
  private final Project myProject;

  public BzrOutgoingChangesProvider(Project project) {
    myProject = project;
  }

  public Pair<VcsRevisionNumber, List<CommittedChangeList>> getOutgoingChanges(final VirtualFile vcsRoot, final boolean findRemote)
    throws VcsException {
    LOG.debug("getOutgoingChanges root: " + vcsRoot.getPath());
    final BzrBranchesSearcher searcher = new BzrBranchesSearcher(myProject, vcsRoot, findRemote);
    if (searcher.getLocal() == null || searcher.getRemote() == null) {
      return new Pair<VcsRevisionNumber, List<CommittedChangeList>>(null, Collections.<CommittedChangeList>emptyList());
    }
    final BzrRevisionNumber base = getMergeBase(myProject, vcsRoot, searcher.getLocal(), searcher.getRemote());
    if (base == null) {
      return new Pair<VcsRevisionNumber, List<CommittedChangeList>>(null, Collections.<CommittedChangeList>emptyList());
    }
    final List<BzrCommittedChangeList> lists = BzrUtil.getLocalCommittedChanges(myProject, vcsRoot, new Consumer<BzrSimpleHandler>() {
      public void consume(final BzrSimpleHandler handler) {
        handler.addParameters(base.asString() + "..HEAD");
      }
    });
    return new Pair<VcsRevisionNumber, List<CommittedChangeList>>(base, ObjectsConvertor.convert(lists, new Convertor<BzrCommittedChangeList, CommittedChangeList>() {
      @Override
      public CommittedChangeList convert(BzrCommittedChangeList o) {
        return o;
      }
    }));
  }

  @Nullable
  public VcsRevisionNumber getMergeBaseNumber(final VirtualFile anyFileUnderRoot) throws VcsException {
    LOG.debug("getMergeBaseNumber parameter: " + anyFileUnderRoot.getPath());
    final ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
    final VirtualFile root = vcsManager.getVcsRootFor(anyFileUnderRoot);
    if (root == null) {
      LOG.info("VCS root not found");
      return null;
    }

    final BzrBranchesSearcher searcher = new BzrBranchesSearcher(myProject, root, true);
    if (searcher.getLocal() == null || searcher.getRemote() == null) {
      LOG.info("local or remote not found");
      return null;
    }
    final BzrRevisionNumber base = getMergeBase(myProject, root, searcher.getLocal(), searcher.getRemote());
    LOG.debug("found base: " + ((base == null) ? null : base.asString()));
    return base;
  }

  public Collection<Change> filterLocalChangesBasedOnLocalCommits(final Collection<Change> localChanges, final VirtualFile vcsRoot) throws VcsException {
    final BzrBranchesSearcher searcher = new BzrBranchesSearcher(myProject, vcsRoot, true);
    if (searcher.getLocal() == null || searcher.getRemote() == null) {
      return new ArrayList<Change>(localChanges); // no information, better strict approach (see getOutgoingChanges() code)
    }
    final BzrRevisionNumber base;
    try {
      base = getMergeBase(myProject, vcsRoot, searcher.getLocal(), searcher.getRemote());
    } catch (VcsException e) {
      LOG.info(e);
      return new ArrayList<Change>(localChanges);
    }
    if (base == null) {
      return new ArrayList<Change>(localChanges); // no information, better strict approach (see getOutgoingChanges() code)
    }
    final List<Pair<SHAHash, Date>> hashes = BzrHistoryUtils
      .onlyHashesHistory(myProject, new FilePathImpl(vcsRoot), vcsRoot, (base.asString() + "..HEAD"));

    if (hashes.isEmpty()) return Collections.emptyList(); // no local commits
    final String first = hashes.get(0).getFirst().getValue(); // optimization
    final Set<String> localHashes = new HashSet<String>();
    for (Pair<SHAHash, Date> hash : hashes) {
      localHashes.add(hash.getFirst().getValue());
    }
    final Collection<Change> result = new ArrayList<Change>();
    for (Change change : localChanges) {
      if (change.getBeforeRevision() != null) {
        final String changeBeforeRevision = change.getBeforeRevision().getRevisionNumber().asString().trim();
        if (first.equals(changeBeforeRevision) || localHashes.contains(changeBeforeRevision)) {
          result.add(change);
        }
      }
    }
    return result;
  }

  @Nullable
  public Date getRevisionDate(VcsRevisionNumber revision, FilePath file) {
    if (VcsRevisionNumber.NULL.equals(revision)) return null;
    try {
      return new Date(BzrHistoryUtils.getAuthorTime(myProject, file, revision.asString()));
    }
    catch (VcsException e) {
      return null;
    }
  }

  /**
   * Get a merge base between the current branch and specified branch.
   * @return the common commit or null if the there is no common commit
   */
  @Nullable
  private static BzrRevisionNumber getMergeBase(@NotNull Project project, @NotNull VirtualFile root,
                                                @NotNull BzrBranch currentBranch, @NotNull BzrBranch branch) throws VcsException {
    return BzrHistoryUtils.getMergeBase(project, root, currentBranch.getFullName(), branch.getFullName());
  }
}
