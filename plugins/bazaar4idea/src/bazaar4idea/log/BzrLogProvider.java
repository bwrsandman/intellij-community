/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package bazaar4idea.log;

import bazaar4idea.BzrLocalBranch;
import bazaar4idea.BzrRemoteBranch;
import bazaar4idea.BzrVcs;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.history.BzrHistoryUtils;
import bazaar4idea.history.BzrLogParser;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryChangeListener;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.*;
import com.intellij.vcs.log.impl.HashImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Kirill Likhodedov
 */
public class BzrLogProvider implements VcsLogProvider {

  private static final Logger LOG = Logger.getInstance(BzrLogProvider.class);

  @NotNull private final Project myProject;
  @NotNull private final BzrRepositoryManager myRepositoryManager;
  @NotNull private final VcsLogRefManager myRefSorter;
  @NotNull private final VcsLogObjectsFactory myVcsObjectsFactory;

  public BzrLogProvider(@NotNull Project project, @NotNull BzrRepositoryManager repositoryManager) {
    myProject = project;
    myRepositoryManager = repositoryManager;
    myRefSorter = new BzrRefManager(myRepositoryManager);
    myVcsObjectsFactory = ServiceManager.getService(myProject, VcsLogObjectsFactory.class);
  }

  @NotNull
  @Override
  public List<? extends VcsFullCommitDetails> readFirstBlock(@NotNull VirtualFile root,
                                                             boolean ordered, int commitCount) throws VcsException {
    if (!isRepositoryReady(root)) {
      return Collections.emptyList();
    }

    String[] params = ArrayUtil.mergeArrays(ArrayUtil.toStringArray(BzrHistoryUtils.LOG_ALL),
                                            "--encoding=UTF-8", "--full-history", "--sparse", "--max-count=" + commitCount);
    if (ordered) {
      params = ArrayUtil.append(params, "--date-order");
    }
    return BzrHistoryUtils.history(myProject, root, params);
  }

  @NotNull
  @Override
  public List<TimedVcsCommit> readAllHashes(@NotNull VirtualFile root, @NotNull Consumer<VcsUser> userRegistry) throws VcsException {
    if (!isRepositoryReady(root)) {
      return Collections.emptyList();
    }

    return BzrHistoryUtils.readAllHashes(myProject, root, userRegistry);
  }

  @NotNull
  @Override
  public List<? extends VcsShortCommitDetails> readShortDetails(@NotNull VirtualFile root, @NotNull List<String> hashes) throws VcsException {
    return BzrHistoryUtils.readMiniDetails(myProject, root, hashes);
  }

  @NotNull
  @Override
  public List<? extends VcsFullCommitDetails> readFullDetails(@NotNull VirtualFile root, @NotNull List<String> hashes) throws VcsException {
    return BzrHistoryUtils.commitsDetails(myProject, root, hashes);
  }

  @NotNull
  @Override
  public Collection<VcsRef> readAllRefs(@NotNull VirtualFile root) throws VcsException {
    if (!isRepositoryReady(root)) {
      return Collections.emptyList();
    }

    BzrRepository repository = getRepository(root);
    repository.update();
    Collection<BzrLocalBranch> localBranches = repository.getBranches().getLocalBranches();
    Collection<BzrRemoteBranch> remoteBranches = repository.getBranches().getRemoteBranches();
    Collection<VcsRef> refs = new ArrayList<VcsRef>(localBranches.size() + remoteBranches.size());
    for (BzrLocalBranch localBranch : localBranches) {
      refs.add(myVcsObjectsFactory.createRef(HashImpl.build(localBranch.getHash()), localBranch.getName(), BzrRefManager.LOCAL_BRANCH, root));
    }
    for (BzrRemoteBranch remoteBranch : remoteBranches) {
      refs.add(myVcsObjectsFactory.createRef(HashImpl.build(remoteBranch.getHash()), remoteBranch.getNameForLocalOperations(),
                          BzrRefManager.REMOTE_BRANCH, root));
    }
    String currentRevision = repository.getCurrentRevision();
    if (currentRevision != null) { // null => fresh repository
      refs.add(myVcsObjectsFactory.createRef(HashImpl.build(currentRevision), "HEAD", BzrRefManager.HEAD, root));
    }

    refs.addAll(readTags(root));
    return refs;
  }

  // TODO this is to be removed when tags will be supported by the BzrRepositoryReader
  private Collection<? extends VcsRef> readTags(@NotNull VirtualFile root) throws VcsException {
    BzrSimpleHandler tagHandler = new BzrSimpleHandler(myProject, root, BzrCommand.LOG);
    tagHandler.setSilent(true);
    tagHandler.addParameters("--tags", "--no-walk", "--format=%H%d" + BzrLogParser.RECORD_START_BZR, "--decorate=full");
    String out = tagHandler.run();
    Collection<VcsRef> refs = new ArrayList<VcsRef>();
    try {
      for (String record : out.split(BzrLogParser.RECORD_START)) {
        if (!StringUtil.isEmptyOrSpaces(record)) {
          refs.addAll(new RefParser(myVcsObjectsFactory).parseCommitRefs(record.trim(), root));
        }
      }
    }
    catch (Exception e) {
      LOG.error("Error during tags parsing", new Attachment("stack_trace.txt", ExceptionUtil.getThrowableText(e)),
                new Attachment("git_output.txt", out));
    }
    return refs;
  }

  @NotNull
  @Override
  public VcsKey getSupportedVcs() {
    return BzrVcs.getKey();
  }

  @NotNull
  @Override
  public VcsLogRefManager getReferenceManager() {
    return myRefSorter;
  }

  @Override
  public void subscribeToRootRefreshEvents(@NotNull final Collection<VirtualFile> roots, @NotNull final VcsLogRefresher refresher) {
    myProject.getMessageBus().connect(myProject).subscribe(BzrRepository.BAZAAR_REPO_CHANGE, new BzrRepositoryChangeListener() {
      @Override
      public void repositoryChanged(@NotNull BzrRepository repository) {
        VirtualFile root = repository.getRoot();
        if (roots.contains(root)) {
          refresher.refresh(root);
        }
      }
    });
  }

  @NotNull
  @Override
  public List<? extends VcsFullCommitDetails> getFilteredDetails(@NotNull final VirtualFile root,
                                                                 @NotNull VcsLogFilterCollection filterCollection,
                                                                 int maxCount) throws VcsException {
    if (!isRepositoryReady(root)) {
      return Collections.emptyList();
    }

    List<String> filterParameters = ContainerUtil.newArrayList();

    if (filterCollection.getBranchFilter() != null) {
      // git doesn't support filtering by several branches very well (--branches parameter give a weak pattern capabilities)
      // => by now assuming there is only one branch filter.
      if (filterCollection.getBranchFilter().getBranchNames().size() > 1) {
        LOG.warn("More than one branch filter was passed. Using only the first one.");
      }
      String branch = filterCollection.getBranchFilter().getBranchNames().iterator().next();
      BzrRepository repository = getRepository(root);
      assert repository != null : "repository is null for root " + root + " but was previously reported as 'ready'";
      if (repository.getBranches().findBranchByName(branch) == null) {
        return Collections.emptyList();
      }
      filterParameters.add(branch);
    }
    else {
      filterParameters.addAll(BzrHistoryUtils.LOG_ALL);
    }

    if (filterCollection.getUserFilter() != null) {
      String authorFilter = StringUtil.join(filterCollection.getUserFilter().getUserNames(root), "|");
      filterParameters.add(prepareParameter("author", StringUtil.escapeChar(StringUtil.escapeBackSlashes(authorFilter), '|')));
    }

    if (filterCollection.getDateFilter() != null) {
      // assuming there is only one date filter, until filter expressions are defined
      VcsLogDateFilter filter = filterCollection.getDateFilter();
      if (filter.getAfter() != null) {
        filterParameters.add(prepareParameter("after", filter.getAfter().toString()));
      }
      if (filter.getBefore() != null) {
        filterParameters.add(prepareParameter("before", filter.getBefore().toString()));
      }
    }

    if (filterCollection.getTextFilter() != null) {
      String textFilter = StringUtil.escapeBackSlashes(filterCollection.getTextFilter().getText());
      filterParameters.add(prepareParameter("grep", textFilter));
    }

    filterParameters.add("--regexp-ignore-case"); // affects case sensitivity of any filter (except file filter)
    if (maxCount > 0) {
      filterParameters.add(prepareParameter("max-count", String.valueOf(maxCount)));
    }
    filterParameters.add("--date-order");

    // note: this filter must be the last parameter, because it uses "--" which separates parameters from paths
    if (filterCollection.getStructureFilter() != null) {
      filterParameters.add("--");
      for (VirtualFile file : filterCollection.getStructureFilter().getFiles(root)) {
        filterParameters.add(file.getPath());
      }
    }

    return BzrHistoryUtils.getAllDetails(myProject, root, filterParameters);
  }

  @Nullable
  @Override
  public VcsUser getCurrentUser(@NotNull VirtualFile root) throws VcsException {
    String userName = BzrConfigUtil.getValue(myProject, root, BzrConfigUtil.USER_NAME);
    String userEmail = StringUtil.notNullize(BzrConfigUtil.getValue(myProject, root, BzrConfigUtil.USER_EMAIL));
    return userName == null ? null : myVcsObjectsFactory.createUser(userName, userEmail);
  }

  @NotNull
  @Override
  public Collection<String> getContainingBranches(@NotNull VirtualFile root, @NotNull Hash commitHash) throws VcsException {
    return BzrBranchUtil.getBranches(myProject, root, true, true, commitHash.asString());
  }

  private static String prepareParameter(String paramName, String value) {
    return "--" + paramName + "=" + value; // no value escaping needed, because the parameter itself will be quoted by GeneralCommandLine
  }

  private static <T> String joinFilters(Collection<T> filters, Function<T, String> toString) {
    return StringUtil.join(filters, toString, "\\|");
  }

  @Nullable
  private BzrRepository getRepository(@NotNull VirtualFile root) {
    myRepositoryManager.waitUntilInitialized();
    return myRepositoryManager.getRepositoryForRoot(root);
  }

  private boolean isRepositoryReady(@NotNull VirtualFile root) {
    BzrRepository repository = getRepository(root);
    if (repository == null) {
      LOG.error("Repository not found for root " + root);
      return false;
    }
    else if (repository.isFresh()) {
      LOG.info("Fresh repository: " + root);
      return false;
    }
    return true;
  }

}