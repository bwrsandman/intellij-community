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
package bazaar4idea.repo;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrLocalBranch;
import bazaar4idea.BzrRemoteBranch;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.branch.BzrBranchesCollection;
import com.intellij.dvcs.repo.RepoStateException;
import com.intellij.dvcs.repo.Repository;
import com.intellij.dvcs.repo.RepositoryUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.Processor;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.impl.HashImpl;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads information about the Bazaar repository from Bazaar service files located in the {@code .bzr} folder.
 * NB: works with {@link java.io.File}, i.e. reads from disk. Consider using caching.
 * Throws a {@link RepoStateException} in the case of incorrect Bazaar file format.
 *
 * @author Kirill Likhodedov
 */
class BzrRepositoryReader {

  private static final Logger LOG = Logger.getInstance(BzrRepositoryReader.class);

  private static Pattern REVISION_PATTERN        = Pattern.compile("(\\d+) (\\S+)");           // revision in .bzr/branch/last-revision
  // this format shouldn't appear, but we don't want to fail because of a space
  private static Pattern BRANCH_WEAK_PATTERN     = Pattern.compile(" *(ref:)? */?refs/heads/(\\S+)");
  private static Pattern COMMIT_PATTERN          = Pattern.compile("[0-9a-fA-F]+"); // commit hash

  @NonNls private static final String REFS_HEADS_PREFIX = "refs/heads/";
  @NonNls private static final String REFS_REMOTES_PREFIX = "refs/remotes/";

  @NotNull private final File myBzrDir;            // .bzr/
  @NotNull private final File myBranchDir;         // .bzr/branch/
  @NotNull private final File myBranchConf;        // .bzr/branch/branch.conf
  @NotNull private final File myBranchFormatFile;  // .bzr/branch/format
  @NotNull private final File myLastRevisionFile;  // .bzr/branch/last-revision
  @NotNull private final File myTagsFile;          // .bzr/branch/tags
  @NotNull private final File myBranchLockDir;     // .bzr/branch-lock/
  @NotNull private final File myCheckoutDir;       // .bzr/checkout/
  @NotNull private final File myRepositoryDir;     // .bzr/repository/

  BzrRepositoryReader(@NotNull File bzrDir) {
    myBzrDir = bzrDir;
    RepositoryUtil.assertFileExists(myBzrDir, ".bzr directory not found in " + bzrDir);
    // .bzr/
    myBranchDir = new File(myBzrDir, "branch");
    RepositoryUtil.assertFileExists(myBranchDir, ".bzr/branch directory not found in " + bzrDir);
    // .bzr/branch
    myBranchConf = new File(myBranchDir, "branch.conf");
    RepositoryUtil.assertFileExists(myBranchConf, ".bzr/branch/branch.conf file not found in " + bzrDir);
    myBranchFormatFile = new File(myBranchDir, "format");
    RepositoryUtil.assertFileExists(myBranchFormatFile, ".bzr/branch/format file not found in " + bzrDir);
    myLastRevisionFile = new File(myBranchDir, "last-revision");
    RepositoryUtil.assertFileExists(myLastRevisionFile, ".bzr/branch/last-revision file not found in " + bzrDir);
    myTagsFile = new File(myBranchDir, "tags");
    RepositoryUtil.assertFileExists(myTagsFile, ".bzr/branch/tags file not found in " + bzrDir);

    myBranchLockDir = new File(myBzrDir, "branch-lock");
    RepositoryUtil.assertFileExists(myBranchLockDir, ".bzr/branch-lock directory not found in " + bzrDir);
    // .bzr/branch-lock

    myCheckoutDir = new File(myBzrDir, "checkout");
    RepositoryUtil.assertFileExists(myCheckoutDir, ".bzr/checkout directory not found in " + bzrDir);
    // .bzr/checkout

    myRepositoryDir = new File(myBzrDir, "repository");
    RepositoryUtil.assertFileExists(myRepositoryDir, ".bzr/repository directory not found in " + bzrDir);
    // .bzr/repositories
  }

  @Nullable
  private static Hash createHash(@Nullable String hash) {
    try {
      return hash == null ? BzrBranch.DUMMY_HASH : HashImpl.build(hash);
    }
    catch (Throwable t) {
      LOG.info(t);
      return null;
    }
  }

  @NotNull
  public Repository.State readState() {
    if (isMergeInProgress()) {
      return Repository.State.MERGING;
    }
    if (isRebaseInProgress()) {
      return Repository.State.REBASING;
    }
    Head head = readHead();
    if (!head.isBranch) {
      return Repository.State.DETACHED;
    }
    return Repository.State.NORMAL;
  }

  /**
   * Finds current revision value.
   * @return The current revision hash, or <b>{@code null}</b> if current revision is unknown - it is the initial repository state.
   */
  @Nullable
  String readCurrentRevision() {
    final Head head = readHead();
    if (!head.isBranch) { // .git/HEAD is a commit
      return head.ref;
    }

    // look in /refs/heads/<branch name>
    File branchFile = null;
    for (Map.Entry<String, File> entry : readLocalBranches().entrySet()) {
      if (entry.getKey().equals(head.ref)) {
        branchFile = entry.getValue();
      }
    }
    if (branchFile != null) {
      return readBranchFile(branchFile);
    }

    // finally look in packed-refs
    return findBranchRevisionInPackedRefs(head.ref);
  }

  /**
   * If the repository is on branch, returns the current branch
   * If the repository is being rebased, returns the branch being rebased.
   * In other cases of the detached HEAD returns {@code null}.
   */
  @Nullable
  BzrLocalBranch readCurrentBranch() {
    Head head = readHead();
    if (head.isBranch) {
      String branchName = head.ref;
      String hash = readCurrentRevision();  // TODO we know the branch name, so no need to read head twice
      Hash h = createHash(hash);
      if (h == null) {
        return null;
      }
      return new BzrLocalBranch(branchName, h);
    }
    if (isRebaseInProgress()) {
      BzrLocalBranch branch = readRebaseBranch("rebase-apply");
      if (branch == null) {
        branch = readRebaseBranch("rebase-merge");
      }
      return branch;
    }
    return null;
  }

  /**
   * Reads {@code .git/rebase-apply/head-name} or {@code .git/rebase-merge/head-name} to find out the branch which is currently being rebased,
   * and returns the {@link bazaar4idea.BzrBranch} for the branch name written there, or null if these files don't exist.
   */
  @Nullable
  private BzrLocalBranch readRebaseBranch(@NonNls String rebaseDirName) {
    File rebaseDir = new File(myBzrDir, rebaseDirName);
    if (!rebaseDir.exists()) {
      return null;
    }
    File headName = new File(rebaseDir, "head-name");
    if (!headName.exists()) {
      return null;
    }
    String branchName = RepositoryUtil.tryLoadFile(headName);
    File branchFile = findBranchFile(branchName);
    if (!branchFile.exists()) { // can happen when rebasing from detached HEAD: IDEA-93806
      return null;
    }
    Hash hash = createHash(readBranchFile(branchFile));
    if (hash == null) {
      return null;
    }
    if (branchName.startsWith(REFS_HEADS_PREFIX)) {
      branchName = branchName.substring(REFS_HEADS_PREFIX.length());
    }
    return new BzrLocalBranch(branchName, hash);
  }

  @NotNull
  private File findBranchFile(@NotNull String branchName) {
    return new File(myBzrDir.getPath() + File.separator + branchName);
  }

  private boolean isMergeInProgress() {
    File mergeHead = new File(myBzrDir, "MERGE_HEAD");
    return mergeHead.exists();
  }

  private boolean isRebaseInProgress() {
    File f = new File(myBzrDir, "rebase-apply");
    if (f.exists()) {
      return true;
    }
    f = new File(myBzrDir, "rebase-merge");
    return f.exists();
  }

  /**
   * Reads the {@code .git/packed-refs} file and tries to find the revision hash for the given reference (branch actually).
   * @param ref short name of the reference to find. For example, {@code master}.
   * @return commit hash, or {@code null} if the given ref wasn't found in {@code packed-refs}
   */
  @Nullable
  private String findBranchRevisionInPackedRefs(final String ref) {
    // TODO make bzr
    return null;
  }

  private void readPackedRefsFile(@NotNull final PackedRefsLineResultHandler handler) {
    RepositoryUtil.tryOrThrow(new Callable<String>() {
      @Override
      public String call() throws Exception {
        // TODO make bzr
        return null;
      }
    }, null);
  }

  /**
   * @return the list of local branches in this Bazaar repository.
   *         key is the branch name, value is the file.
   */
  private Map<String, File> readLocalBranches() {
    final Map<String, File> branches = new HashMap<String, File>();
    if (!myCheckoutDir.exists()) {
      return branches;
    }
    FileUtil.processFilesRecursively(myCheckoutDir, new Processor<File>() {
      @Override
      public boolean process(File file) {
        if (!file.isDirectory()) {
          String relativePath = FileUtil.getRelativePath(myCheckoutDir, file);
          if (relativePath != null) {
            branches.put(FileUtil.toSystemIndependentName(relativePath), file);
          }
        }
        return true;
      }
    });
    return branches;
  }

  /**
   * @return all branches in this repository. local/remote/active information is stored in branch objects themselves.
   * @param remotes
   */
  BzrBranchesCollection readBranches(@NotNull Collection<BzrRemote> remotes) {
    Set<BzrLocalBranch> localBranches = readUnpackedLocalBranches();
    BzrBranchesCollection packedBranches = readPackedBranches(remotes);
    localBranches.addAll(packedBranches.getLocalBranches());
    Collection<BzrRemoteBranch> remoteBranches = packedBranches.getRemoteBranches();
    return new BzrBranchesCollection(localBranches, remoteBranches);
  }

  /**
   * @return list of branches from refs/heads. active branch is not marked as active - the caller should do this.
   */
  @NotNull
  private Set<BzrLocalBranch> readUnpackedLocalBranches() {
    Set<BzrLocalBranch> branches = new HashSet<BzrLocalBranch>();
    for (Map.Entry<String, File> entry : readLocalBranches().entrySet()) {
      String branchName = entry.getKey();
      File branchFile = entry.getValue();
      String hash = loadHashFromBranchFile(branchFile);
      Hash h = createHash(hash);
      if (h != null) {
        branches.add(new BzrLocalBranch(branchName, h));
      }
    }
    return branches;
  }

  @Nullable
  private static String loadHashFromBranchFile(@NotNull File branchFile) {
    try {
      return RepositoryUtil.tryLoadFile(branchFile);
    }
    catch (RepoStateException e) {  // notify about error but don't break the process
      LOG.error("Couldn't read " + branchFile, e);
    }
    return null;
  }

  /**
   * @return list of local and remote branches from packed-refs. Active branch is not marked as active.
   * @param remotes
   */
  @NotNull
  private BzrBranchesCollection readPackedBranches(@NotNull final Collection<BzrRemote> remotes) {
    final Set<BzrLocalBranch> localBranches = new HashSet<BzrLocalBranch>();
    final Set<BzrRemoteBranch> remoteBranches = new HashSet<BzrRemoteBranch>();
    // TODO make bzr
    if (true) {
      return BzrBranchesCollection.EMPTY;
    }

    readPackedRefsFile(new PackedRefsLineResultHandler() {
      @Override public void handleResult(@Nullable String hashString, @Nullable String branchName) {
        if (hashString == null || branchName == null) {
          return;
        }
        hashString = shortBuffer(hashString);
        Hash hash = createHash(hashString);
        if (hash == null) {
          return;
        }
        if (branchName.startsWith(REFS_HEADS_PREFIX)) {
          localBranches.add(new BzrLocalBranch(branchName, hash));
        }
        else if (branchName.startsWith(REFS_REMOTES_PREFIX)) {
          BzrRemoteBranch remoteBranch = BzrBranchUtil.parseRemoteBranch(branchName, hash, remotes);
          if (remoteBranch != null) {
            remoteBranches.add(remoteBranch);
          }
        }
      }
    });
    return new BzrBranchesCollection(localBranches, remoteBranches);
  }

  @NotNull
  private static String readBranchFile(@NotNull File branchFile) {
    return RepositoryUtil.tryLoadFile(branchFile);
  }

  @NotNull
  private Head readHead() {
    String headContent = RepositoryUtil.tryLoadFile(myLastRevisionFile);
    Matcher matcher = REVISION_PATTERN.matcher(headContent);
    if (matcher.matches()) {
      return new Head(true, matcher.group(1), matcher.group(2));
    }

    if (COMMIT_PATTERN.matcher(headContent).matches()) {
      return new Head(false, headContent, "");
    }
    matcher = BRANCH_WEAK_PATTERN.matcher(headContent);
    if (matcher.matches()) {
      LOG.info(".git/HEAD has not standard format: [" + headContent + "]. We've parsed branch [" + matcher.group(1) + "]");
      return new Head(true, matcher.group(1), "");
    }
    throw new RepoStateException("Invalid format of the .git/HEAD file: \n" + headContent);
  }

  @NotNull
  private static String shortBuffer(String raw) {
    return new String(raw);
  }

  private abstract static class PackedRefsLineResultHandler {
    private boolean myStopped;

    abstract void handleResult(@Nullable String hash, @Nullable String branchName);

    /**
     * Call this to stop further lines reading.
     */
    final void stop() {
      myStopped = true;
    }

    final boolean stopped() {
      return myStopped;
    }
  }

  /**
   * Container to hold two information items: current .git/HEAD value and is Bazaar on branch.
   */
  private static class Head {
    @NotNull private final String ref;
    private final String info;
    private final boolean isBranch;

    Head(boolean branch, @NotNull String r, String i) {
      isBranch = branch;
      ref = r;
      info = i;
    }
  }


}
