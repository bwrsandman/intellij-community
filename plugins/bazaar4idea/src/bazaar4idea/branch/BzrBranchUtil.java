/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package bazaar4idea.branch;

import bazaar4idea.commands.BzrCommand;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.config.BzrVcsSettings;
import bazaar4idea.ui.branch.BzrMultiRootBranchConfig;
import bazaar4idea.validators.BzrNewBranchNameValidator;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.impl.status.StatusBarUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.Hash;
import com.intellij.vcsUtil.VcsUtil;
import bazaar4idea.*;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.repo.*;
import org.intellij.images.editor.ImageFileEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author Kirill Likhodedov
 */
public class BzrBranchUtil {

  private static final Logger LOG = Logger.getInstance(BzrBranchUtil.class);

  private static final Function<BzrBranch,String> BRANCH_TO_NAME = new Function<BzrBranch, String>() {
    @Override
    public String apply(@Nullable BzrBranch input) {
      assert input != null;
      return input.getName();
    }
  };
  // The name that specifies that git is on specific commit rather then on some branch ({@value})
 private static final String NO_BRANCH_NAME = "(no branch)";

  private BzrBranchUtil() {}

  @NotNull
  static String getCurrentBranchOrRev(@NotNull Collection<BzrRepository> repositories) {
    if (repositories.size() > 1) {
      BzrMultiRootBranchConfig multiRootBranchConfig = new BzrMultiRootBranchConfig(repositories);
      String currentBranch = multiRootBranchConfig.getCurrentBranch();
      LOG.assertTrue(currentBranch != null, "Repositories have unexpectedly diverged. " + multiRootBranchConfig);
      return currentBranch;
    }
    else {
      assert !repositories.isEmpty() : "No repositories passed to BzrBranchOperationsProcessor.";
      BzrRepository repository = repositories.iterator().next();
      return getBranchNameOrRev(repository);
    }
  }

  @NotNull
  public static Collection<String> convertBranchesToNames(@NotNull Collection<? extends BzrBranch> branches) {
    return Collections2.transform(branches, BRANCH_TO_NAME);
  }

  /**
   * Returns the current branch in the given repository, or null if either repository is not on the branch, or in case of error.
   * @deprecated Use {@link bazaar4idea.repo.BzrRepository#getCurrentBranch()}
   */
  @Deprecated
  @Nullable
  public static BzrLocalBranch getCurrentBranch(@NotNull Project project, @NotNull VirtualFile root) {
    BzrRepository repository = BzrUtil.getRepositoryManager(project).getRepositoryForRoot(root);
    if (repository != null) {
      return repository.getCurrentBranch();
    }
    else {
      LOG.info("getCurrentBranch: Repository is null for root " + root);
      return getCurrentBranchFromBzr(project, root);
    }
  }

  @Nullable
  private static BzrLocalBranch getCurrentBranchFromBzr(@NotNull Project project, @NotNull VirtualFile root) {
    BzrSimpleHandler handler = new BzrSimpleHandler(project, root, BzrCommand.REV_PARSE);
    handler.addParameters("--abbrev-ref", "HEAD");
    //handler.setSilent(true);
    try {
      String name = handler.run();
      if (!name.equals("HEAD")) {
        return new BzrLocalBranch(name, BzrBranch.DUMMY_HASH);
      }
      else {
        return null;
      }
    }
    catch (VcsException e) {
      LOG.info("git rev-parse --abbrev-ref HEAD", e);
      return null;
    }
  }

  /**
   * Get tracked remote for the branch
   */
  @Nullable
  public static String getTrackedRemoteName(Project project, VirtualFile root, String branchName) throws VcsException {
    return BzrConfigUtil.getValue(project, root, trackedRemoteKey(branchName));
  }

  /**
   * Get tracked branch of the given branch
   */
  @Nullable
  public static String getTrackedBranchName(Project project, VirtualFile root, String branchName) throws VcsException {
    return BzrConfigUtil.getValue(project, root, trackedBranchKey(branchName));
  }

  @NotNull
  private static String trackedBranchKey(String branchName) {
    return "branch." + branchName + ".merge";
  }

  @NotNull
  private static String trackedRemoteKey(String branchName) {
    return "branch." + branchName + ".remote";
  }

  /**
   * Get the tracking branch for the given branch, or null if the given branch doesn't track anything.
   * @deprecated Use {@link bazaar4idea.repo.BzrConfig#getBranchTrackInfos()}
   */
  @Deprecated
  @Nullable
  public static BzrRemoteBranch tracked(@NotNull Project project, @NotNull VirtualFile root, @NotNull String branchName) throws VcsException {
    final HashMap<String, String> result = new HashMap<String, String>();
    BzrConfigUtil.getValues(project, root, null, result);
    String remoteName = result.get(trackedRemoteKey(branchName));
    if (remoteName == null) {
      return null;
    }
    String branch = result.get(trackedBranchKey(branchName));
    if (branch == null) {
      return null;
    }

    BzrRemote remote = findRemoteByNameOrLogError(project, root, remoteName);
    if (remote == null) return null;
    return new BzrStandardRemoteBranch(remote, branch, BzrBranch.DUMMY_HASH);
  }

  @Nullable
  @Deprecated
  public static BzrRemote findRemoteByNameOrLogError(@NotNull Project project, @NotNull VirtualFile root, @NotNull String remoteName) {
    BzrRepository repository = BzrUtil.getRepositoryForRootOrLogError(project, root);
    if (repository == null) {
      return null;
    }

    BzrRemote remote = BzrUtil.findRemoteByName(repository, remoteName);
    if (remote == null) {
      LOG.warn("Couldn't find remote with name " + remoteName);
      return null;
    }
    return remote;
  }

  /**
   *
   * @return {@link bazaar4idea.BzrStandardRemoteBranch} or {@link bazaar4idea.BzrSvnRemoteBranch}, or null in case of an error. The error is logged in this method.
   * @deprecated Should be used only in the BzrRepositoryReader, i. e. moved there once all other usages are removed.
   */
  @Deprecated
  @Nullable
  public static BzrRemoteBranch parseRemoteBranch(@NotNull String fullBranchName, @NotNull Hash hash,
                                                  @NotNull Collection<BzrRemote> remotes) {
    String stdName = stripRefsPrefix(fullBranchName);

    int slash = stdName.indexOf('/');
    String remoteName = stdName.substring(0, slash);
    String branchName = stdName.substring(slash + 1);
    BzrRemote remote = findRemoteByName(remoteName, remotes);
    if (remote == null) {
      return null;
    }
    return new BzrStandardRemoteBranch(remote, branchName, hash);
  }

  @Nullable
  private static BzrRemote findRemoteByName(@NotNull String remoteName, @NotNull Collection<BzrRemote> remotes) {
    for (BzrRemote remote : remotes) {
      if (remote.getName().equals(remoteName)) {
        return remote;
      }
    }
    // user may remove the remote section from .git/config, but leave remote refs untouched in .git/refs/remotes
    LOG.info(String.format("No remote found with the name [%s]. All remotes: %s", remoteName, remotes));
    return null;
  }

  /**
   * Convert {@link bazaar4idea.BzrRemoteBranch BzrRemoteBranches} to their names, and remove remote HEAD pointers: origin/HEAD.
   */
  @NotNull
  public static Collection<String> getBranchNamesWithoutRemoteHead(@NotNull Collection<BzrRemoteBranch> remoteBranches) {
    return Collections2.filter(convertBranchesToNames(remoteBranches), new Predicate<String>() {
      @Override
      public boolean apply(@Nullable String input) {
        assert input != null;
        return !input.equals("HEAD");
      }
    });
  }

  /**
   * @deprecated Don't use names, use {@link bazaar4idea.BzrLocalBranch} objects.
   */
  @Deprecated
  @Nullable
  public static BzrLocalBranch findLocalBranchByName(@NotNull BzrRepository repository, @NotNull final String branchName) {
    Optional<BzrLocalBranch> optional = Iterables.tryFind(repository.getBranches().getLocalBranches(), new Predicate<BzrLocalBranch>() {
      @Override
      public boolean apply(@Nullable BzrLocalBranch input) {
        assert input != null;
        return input.getName().equals(branchName);
      }
    });
    if (optional.isPresent()) {
      return optional.get();
    }
    LOG.info(String.format("Couldn't find branch with name %s in %s", branchName, repository));
    return null;

  }

  /**
   * Looks through the remote branches in the given repository and tries to find the one from the given remote,
   * which the given name.
   * @return remote branch or null if such branch couldn't be found.
   */
  @Nullable
  public static BzrRemoteBranch findRemoteBranchByName(@NotNull String remoteBranchName, @NotNull final String remoteName,
                                                       @NotNull final Collection<BzrRemoteBranch> remoteBranches) {
    final String branchName = stripRefsPrefix(remoteBranchName);
    Optional<BzrRemoteBranch> optional = Iterables.tryFind(remoteBranches, new Predicate<BzrRemoteBranch>() {
      @Override
      public boolean apply(@Nullable BzrRemoteBranch input) {
        assert input != null;
        return input.getNameForRemoteOperations().equals(branchName) && input.getRemote().getName().equals(remoteName);
      }
    });
    if (optional.isPresent()) {
      return optional.get();
    }
    LOG.info(String.format("Couldn't find branch with name %s", branchName));
    return null;
  }

  @NotNull
  public static String stripRefsPrefix(@NotNull String branchName) {
    if (branchName.startsWith(BzrBranch.REFS_HEADS_PREFIX)) {
      return branchName.substring(BzrBranch.REFS_HEADS_PREFIX.length());
    }
    else if (branchName.startsWith(BzrBranch.REFS_REMOTES_PREFIX)) {
      return branchName.substring(BzrBranch.REFS_REMOTES_PREFIX.length());
    }
    return branchName;
  }

  /**
   * Returns current branch name (if on branch) or current revision otherwise.
   * For fresh repository returns an empty string.
   */
  @NotNull
  public static String getBranchNameOrRev(@NotNull BzrRepository repository) {
    if (repository.isOnBranch()) {
      BzrBranch currentBranch = repository.getCurrentBranch();
      assert currentBranch != null;
      return currentBranch.getName();
    } else {
      String currentRevision = repository.getCurrentRevision();
      return currentRevision != null ? currentRevision.substring(0, 7) : "";
    }
  }

  /**
   * Shows a message dialog to enter the name of new branch.
   * @return name of new branch or {@code null} if user has cancelled the dialog.
   */
  @Nullable
  public static String getNewBranchNameFromUser(@NotNull Project project, @NotNull Collection<BzrRepository> repositories, @NotNull String dialogTitle) {
    return Messages.showInputDialog(project, "Enter the name of new branch:", dialogTitle, Messages.getQuestionIcon(), "",
                                    BzrNewBranchNameValidator.newInstance(repositories));
  }

  /**
   * Returns the text that is displaying current branch.
   * In the simple case it is just the branch name, but in detached HEAD state it displays the hash or "rebasing master".
   */
  public static String getDisplayableBranchText(@NotNull BzrRepository repository) {
    BzrRepository.State state = repository.getState();
    if (state == BzrRepository.State.DETACHED) {
      String currentRevision = repository.getCurrentRevision();
      assert currentRevision != null : "Current revision can't be null in DETACHED state, only on the fresh repository.";
      return currentRevision.substring(0, 7);
    }

    String prefix = "";
    if (state == BzrRepository.State.MERGING || state == BzrRepository.State.REBASING) {
      prefix = state.toString() + " ";
    }

    BzrBranch branch = repository.getCurrentBranch();
    String branchName = (branch == null ? "" : branch.getName());
    return prefix + branchName;
  }

  /**
   * Returns the currently selected file, based on which BzrBranch components ({@link bazaar4idea.ui.branch.BzrBranchPopup}, {@link bazaar4idea.ui.branch.BzrBranchWidget})
   * will identify the current repository root.
   */
  @Nullable
  static VirtualFile getSelectedFile(@NotNull Project project) {
    StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
    final FileEditor fileEditor = StatusBarUtil.getCurrentFileEditor(project, statusBar);
    VirtualFile result = null;
    if (fileEditor != null) {
      if (fileEditor instanceof TextEditor) {
        Document document = ((TextEditor)fileEditor).getEditor().getDocument();
        result = FileDocumentManager.getInstance().getFile(document);
      } else if (fileEditor instanceof ImageFileEditor) {
        result = ((ImageFileEditor)fileEditor).getImageEditor().getFile();
      }
    }

    if (result == null) {
      final FileEditorManager manager = FileEditorManager.getInstance(project);
      if (manager != null) {
        Editor editor = manager.getSelectedTextEditor();
        if (editor != null) {
          result = FileDocumentManager.getInstance().getFile(editor.getDocument());
        }
      }
    }
    return result;
  }

  /**
   * Guesses the Bazaar root on which a Bazaar action is to be invoked.
   * <ol>
   *   <li>
   *     Returns the root for the selected file. Selected file is determined by {@link #getSelectedFile(com.intellij.openapi.project.Project)}.
   *     If selected file is unknown (for example, no file is selected in the Project View or Changes View and no file is open in the editor),
   *     continues guessing. Otherwise returns the Bazaar root for the selected file. If the file is not under a known Bazaar root,
   *     <code>null</code> will be returned - the file is definitely determined, but it is not under Bazaar.
   *   </li>
   *   <li>
   *     Takes all Bazaar roots registered in the Project. If there is only one, it is returned.
   *   </li>
   *   <li>
   *     If there are several Bazaar roots,
   *   </li>
   * </ol>
   *
   * <p>
   *   NB: This method has to be accessed from the <b>read action</b>, because it may query
   *   {@link com.intellij.openapi.fileEditor.FileEditorManager#getSelectedTextEditor()}.
   * </p>
   * @param project current project
   * @return Bazaar root that may be considered as "current".
   *         <code>null</code> is returned if a file not under Bazaar was explicitly selected, if there are no Bazaar roots in the project,
   *         or if the current Bazaar root couldn't be determined.
   */
  @Nullable
  public static BzrRepository getCurrentRepository(@NotNull Project project) {
    BzrRepositoryManager manager = BzrUtil.getRepositoryManager(project);
    VirtualFile file = getSelectedFile(project);
    VirtualFile root = getVcsRootOrGuess(project, file);
    return manager.getRepositoryForRoot(root);
  }

  @Nullable
  public static VirtualFile getVcsRootOrGuess(@NotNull Project project, @Nullable VirtualFile file) {
    VirtualFile root = null;
    ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
    if (file != null) {
      if (fileIndex.isInLibrarySource(file) || fileIndex.isInLibraryClasses(file)) {
        LOG.debug("File is in library sources " + file);
        root = getVcsRootForLibraryFile(project, file);
      }
      else {
        LOG.debug("File is not in library sources " + file);
        root = ProjectLevelVcsManager.getInstance(project).getVcsRootFor(file);
      }
    }
    return root != null ? root : guessBzrRoot(project);
  }

  @Nullable
  private static VirtualFile getVcsRootForLibraryFile(@NotNull Project project, @NotNull VirtualFile file) {
    ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
    // for a file inside .jar/.zip consider the .jar/.zip file itself
    VirtualFile root = vcsManager.getVcsRootFor(VfsUtilCore.getVirtualFileForJar(file));
    if (root != null) {
      LOG.debug("Found root for zip/jar file: " + root);
      return root;
    }

    // for other libs which don't have jars inside the project dir (such as JDK) take the owner module of the lib
    List<OrderEntry> entries = ProjectRootManager.getInstance(project).getFileIndex().getOrderEntriesForFile(file);
    Set<VirtualFile> libraryRoots = new HashSet<VirtualFile>();
    for (OrderEntry entry : entries) {
      if (entry instanceof LibraryOrderEntry || entry instanceof JdkOrderEntry) {
        VirtualFile moduleRoot = vcsManager.getVcsRootFor(entry.getOwnerModule().getModuleFile());
        if (moduleRoot != null) {
          libraryRoots.add(moduleRoot);
        }
      }
    }

    if (libraryRoots.size() == 0) {
      LOG.debug("No library roots");
      return null;
    }

    // if the lib is used in several modules, take the top module
    // (for modules of the same level we can't guess anything => take the first one)
    Iterator<VirtualFile> libIterator = libraryRoots.iterator();
    VirtualFile topLibraryRoot = libIterator.next();
    while (libIterator.hasNext()) {
      VirtualFile libRoot = libIterator.next();
      if (VfsUtilCore.isAncestor(libRoot, topLibraryRoot, true)) {
        topLibraryRoot = libRoot;
      }
    }
    LOG.debug("Several library roots, returning " + topLibraryRoot);
    return topLibraryRoot;
  }

  @Nullable
  private static VirtualFile guessBzrRoot(@NotNull Project project) {
    LOG.debug("Guessing Bazaar root...");
    ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
    AbstractVcs bzrVcs = BzrVcs.getInstance(project);
    if (bzrVcs == null) {
      LOG.debug("BzrVcs not found.");
      return null;
    }
    VirtualFile[] bzrRoots = vcsManager.getRootsUnderVcs(bzrVcs);
    if (bzrRoots.length == 0) {
      LOG.debug("No Bazaar roots in the project.");
      return null;
    }

    if (bzrRoots.length == 1) {
      VirtualFile onlyRoot = bzrRoots[0];
      LOG.debug("Only one Bazaar root in the project, returning: " + onlyRoot);
      return onlyRoot;
    }

    // remember the last visited Bazaar root
    BzrVcsSettings settings = BzrVcsSettings.getInstance(project);
    if (settings != null) {
      String recentRootPath = settings.getRecentRootPath();
      if (recentRootPath != null) {
        VirtualFile recentRoot = VcsUtil.getVirtualFile(recentRootPath);
        if (recentRoot != null) {
          LOG.debug("Returning the recent root: " + recentRoot);
          return recentRoot;
        }
      }
    }

    // otherwise return the root of the project dir or the root containing the project dir, if there is such
    VirtualFile projectBaseDir = project.getBaseDir();
    if (projectBaseDir == null) {
      VirtualFile firstRoot = bzrRoots[0];
      LOG.debug("Project base dir is null, returning the first root: " + firstRoot);
      return firstRoot;
    }
    VirtualFile rootCandidate = null;
    for (VirtualFile root : bzrRoots) {
      if (root.equals(projectBaseDir)) {
        return root;
      }
      else if (VfsUtilCore.isAncestor(root, projectBaseDir, true)) {
        rootCandidate = root;
      }
    }
    LOG.debug("Returning the best candidate: " + rootCandidate);
    return rootCandidate;
  }

  @NotNull
  public static Collection<String> getCommonBranches(Collection<BzrRepository> repositories,
                                                     boolean local) {
    Collection<String> commonBranches = null;
    for (BzrRepository repository : repositories) {
      BzrBranchesCollection branchesCollection = repository.getBranches();

      Collection<String> names = local
                                 ? convertBranchesToNames(branchesCollection.getLocalBranches())
                                 : getBranchNamesWithoutRemoteHead(branchesCollection.getRemoteBranches());
      if (commonBranches == null) {
        commonBranches = names;
      }
      else {
        commonBranches = ContainerUtil.intersection(commonBranches, names);
      }
    }

    if (commonBranches != null) {
      ArrayList<String> common = new ArrayList<String>(commonBranches);
      Collections.sort(common);
      return common;
    }
    else {
      return Collections.emptyList();
    }
  }

  /**
   * List branches containing a commit. Specify null if no commit filtering is needed.
   */
  @NotNull
  public static Collection<String> getBranches(@NotNull Project project, @NotNull VirtualFile root, boolean localWanted,
                                               boolean remoteWanted, @Nullable String containingCommit) throws VcsException {
    // preparing native command executor
    final BzrSimpleHandler handler = new BzrSimpleHandler(project, root, BzrCommand.BRANCH);
    //handler.setSilent(true);
    handler.addParameters("--no-color");
    boolean remoteOnly = false;
    if (remoteWanted && localWanted) {
      handler.addParameters("-a");
      remoteOnly = false;
    } else if (remoteWanted) {
      handler.addParameters("-r");
      remoteOnly = true;
    }
    if (containingCommit != null) {
      handler.addParameters("--contains", containingCommit);
    }
    final String output = handler.run();

    if (output.trim().length() == 0) {
      // the case after git init and before first commit - there is no branch and no output, and we'll take refs/heads/master
      String head;
      try {
        head = FileUtil.loadFile(new File(root.getPath(), BzrRepositoryFiles.BAZAAR_HEAD), BzrUtil.UTF8_ENCODING).trim();
        final String prefix = "ref: refs/heads/";
        return head.startsWith(prefix) ?
               Collections.singletonList(head.substring(prefix.length())) :
               Collections.<String>emptyList();
      } catch (IOException e) {
        LOG.info(e);
        return Collections.emptyList();
      }
    }

    Collection<String> branches = ContainerUtil.newArrayList();
    // standard situation. output example:
    //  master
    //* my_feature
    //  remotes/origin/HEAD -> origin/master
    //  remotes/origin/eap
    //  remotes/origin/feature
    //  remotes/origin/master
    // also possible:
    //* (no branch)
    // and if we call with -r instead of -a, remotes/ prefix is omitted:
    // origin/HEAD -> origin/master
    final String[] split = output.split("\n");
    for (String b : split) {
      b = b.substring(2).trim();
      if (b.equals(NO_BRANCH_NAME)) { continue; }

      String remotePrefix = null;
      if (b.startsWith("remotes/")) {
        remotePrefix = "remotes/";
      } else if (b.startsWith(BzrBranch.REFS_REMOTES_PREFIX)) {
        remotePrefix = BzrBranch.REFS_REMOTES_PREFIX;
      }
      boolean isRemote = remotePrefix != null || remoteOnly;
      if (isRemote) {
        if (! remoteOnly) {
          b = b.substring(remotePrefix.length());
        }
        final int idx = b.indexOf("HEAD ->");
        if (idx > 0) {
          continue;
        }
      }
      branches.add(b);
    }
    return branches;
  }
}
