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
package bazaar4idea.history.browser;

import bazaar4idea.BzrTag;
import bazaar4idea.BzrUtil;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.branch.BzrBranchesCollection;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.history.BzrHistoryUtils;
import bazaar4idea.repo.BzrBranchTrackInfo;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryImpl;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Getter;
import com.intellij.openapi.vcs.FilePathImpl;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.AsynchConsumer;
import bazaar4idea.BzrBranch;
import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.history.wholeTree.AbstractHash;
import bazaar4idea.history.wholeTree.CommitHashPlusParents;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public class LowLevelAccessImpl implements LowLevelAccess {
  private final static Logger LOG = Logger.getInstance("#bazaar4idea.history.browser.LowLevelAccessImpl");

  private final Project myProject;
  private final VirtualFile myRoot;

  public LowLevelAccessImpl(final Project project, final VirtualFile root) {
    myProject = project;
    myRoot = root;
  }

  @Override
  public VirtualFile getRoot() {
    return myRoot;
  }

  public void loadHashesWithParents(final @NotNull Collection<String> startingPoints,
                                    @NotNull final Collection<ChangesFilter.Filter> filters,
                                    final AsynchConsumer<CommitHashPlusParents> consumer,
                                    Getter<Boolean> isCanceled, int useMaxCnt, final boolean topoOrder) throws VcsException {
    final List<String> parameters = new ArrayList<String>();
    final Collection<VirtualFile> paths = new HashSet<VirtualFile>();
    ChangesFilter.filtersToParameters(filters, parameters, paths);

    if (! startingPoints.isEmpty()) {
      for (String startingPoint : startingPoints) {
        parameters.add(startingPoint);
      }
    } else {
      parameters.add("HEAD");
      parameters.add("--branches");
      parameters.add("--remotes");
      parameters.add("--tags");
    }
    if (useMaxCnt > 0) {
      parameters.add("--max-count=" + useMaxCnt);
    }
    if (topoOrder) {
      parameters.add("--topo-order");
    } else {
      parameters.add("--date-order");
    }

    BzrHistoryUtils.hashesWithParents(myProject, new FilePathImpl(myRoot), consumer, isCanceled, paths, ArrayUtil.toStringArray(parameters));
  }

  @Override
  public List<BzrHeavyCommit> getCommitDetails(final Collection<String> commitIds, SymbolicRefsI refs) throws VcsException {
    return BzrHistoryUtils.commitsDetails(myProject, new FilePathImpl(myRoot), refs, commitIds);
  }

  // uses cached version
  public CachedRefs getRefs() throws VcsException {
    final CachedRefs refs = new CachedRefs();
    BzrRepository repository = getRepositoryWise(myProject, myRoot);
    BzrBranchesCollection branches = repository.getBranches();
    refs.setCollection(branches);
    final BzrBranch current = repository.getCurrentBranch();
    refs.setCurrentBranch(current);
    refs.setUsername(BzrConfigUtil.getValue(myProject, myRoot, BzrConfigUtil.USER_NAME));
    final String head = repository.getCurrentRevision();
    if (head != null) {
      refs.setHeadHash(AbstractHash.create(head));
    }
    return refs;
  }

  private static BzrRepository getRepositoryWise(final Project project, final VirtualFile root) throws VcsException {
    BzrRepository repository = project == null || project.isDefault() ? null : BzrUtil.getRepositoryManager(project).getRepositoryForRoot(root);
    if (repository == null) {
      final File child = new File(root.getPath(), BzrUtil.DOT_BZR);
      if (! child.exists()) {
        throw new VcsException("No git repository in " + root.getPath());
      }
      repository = BzrRepositoryImpl
        .getLightInstance(root, project, ServiceManager.getService(project, BzrPlatformFacade.class), project);
      repository.update();
      repository.getBranches();
    }
    return repository;
  }

  public void loadCommits(final @NotNull Collection<String> startingPoints, @NotNull final Collection<String> endPoints,
                          @NotNull final Collection<ChangesFilter.Filter> filters,
                          @NotNull final AsynchConsumer<BzrHeavyCommit> consumer,
                          int useMaxCnt,
                          Getter<Boolean> isCanceled, SymbolicRefsI refs, final boolean topoOrder)
    throws VcsException {

    final List<String> parameters = new ArrayList<String>();
    if (useMaxCnt > 0) {
      parameters.add("--max-count=" + useMaxCnt);
    }

    final Collection<VirtualFile> paths = new HashSet<VirtualFile>();
    ChangesFilter.filtersToParameters(filters, parameters, paths);

    if (! startingPoints.isEmpty()) {
      for (String startingPoint : startingPoints) {
        parameters.add(startingPoint);
      }
    } else {
      parameters.add("HEAD");
      parameters.add("--branches");
      parameters.add("--remotes");
      parameters.add("--tags");
    }
    if (topoOrder) {
      parameters.add("--topo-order");
    } else {
      parameters.add("--date-order");
    }

    for (String endPoint : endPoints) {
      parameters.add("^" + endPoint);
    }

    BzrHistoryUtils
      .historyWithLinks(myProject, new FilePathImpl(myRoot), refs, consumer, isCanceled, paths, true, ArrayUtil.toStringArray(parameters));
  }

  public List<String> getBranchesWithCommit(final SHAHash hash) throws VcsException {
    return getBranchesWithCommit(hash.getValue());
  }

  public List<String> getBranchesWithCommit(final String hash) throws VcsException {
    return new ArrayList<String>(BzrBranchUtil.getBranches(myProject, myRoot, true, true, hash));
  }

  public Collection<String> getTagsWithCommit(final SHAHash hash) throws VcsException {
    final List<String> result = new ArrayList<String>();
    BzrTag.listAsStrings(myProject, myRoot, result, hash.getValue());
    return result;
  }
}
