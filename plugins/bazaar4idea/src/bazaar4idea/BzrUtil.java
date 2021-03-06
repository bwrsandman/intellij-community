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
package bazaar4idea;

import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.changes.BzrChangeUtils;
import bazaar4idea.changes.BzrCommittedChangeList;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.repo.BzrBranchTrackInfo;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.util.BzrUIUtil;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.FilePathsHelper;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vcs.vfs.AbstractVcsVirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsFileUtil;
import com.intellij.vcsUtil.VcsUtil;
import bazaar4idea.commands.*;
import bazaar4idea.util.StringScanner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Bazaar utility/helper methods
 */
public class BzrUtil {
  /**
   * Comparator for virtual files by name
   */
  public static final Comparator<VirtualFile> VIRTUAL_FILE_COMPARATOR = new Comparator<VirtualFile>() {
    public int compare(final VirtualFile o1, final VirtualFile o2) {
      if (o1 == null && o2 == null) {
        return 0;
      }
      if (o1 == null) {
        return -1;
      }
      if (o2 == null) {
        return 1;
      }
      return o1.getPresentableUrl().compareTo(o2.getPresentableUrl());
    }
  };
  /**
   * The UTF-8 encoding name
   */
  public static final String UTF8_ENCODING = "UTF-8";
  /**
   * The UTF8 charset
   */
  public static final Charset UTF8_CHARSET = Charset.forName(UTF8_ENCODING);
  public static final String DOT_BZR = ".bzr";

  private final static Logger LOG = Logger.getInstance(BzrUtil.class);
  private static final int SHORT_HASH_LENGTH = 8;

  public static final Predicate<BzrBranchTrackInfo> NOT_NULL_PREDICATE = new Predicate<BzrBranchTrackInfo>() {
    @Override
    public boolean apply(@Nullable BzrBranchTrackInfo input) {
      return input != null;
    }
  };

  public static final SimpleDateFormat COMMIT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ZZZZ");

  /**
   * A private constructor to suppress instance creation
   */
  private BzrUtil() {
    // do nothing
  }

  @Nullable
  public static VirtualFile findBzrDir(@NotNull VirtualFile rootDir) {
    VirtualFile child = rootDir.findChild(DOT_BZR);
    if (child == null) {
      return null;
    }
    if (child.isDirectory()) {
      return child;
    }

    // this is standard for submodules, although probably it can
    String content;
    try {
      content = readFile(child);
    }
    catch (IOException e) {
      throw new RuntimeException("Couldn't read " + child, e);
    }
    String pathToDir;
    String prefix = "gitdir:";
    if (content.startsWith(prefix)) {
      pathToDir = content.substring(prefix.length()).trim();
    }
    else {
      pathToDir = content;
    }

    if (!FileUtil.isAbsolute(pathToDir)) {
      String canonicalPath = FileUtil.toCanonicalPath(FileUtil.join(rootDir.getPath(), pathToDir));
      if (canonicalPath == null) {
        return null;
      }
      pathToDir = FileUtil.toSystemIndependentName(canonicalPath);
    }
    return VcsUtil.getVirtualFileWithRefresh(new File(pathToDir));
  }

  /**
   * Makes 3 attempts to get the contents of the file. If all 3 fail with an IOException, rethrows the exception.
   */
  @NotNull
  public static String readFile(@NotNull VirtualFile file) throws IOException {
    final int ATTEMPTS = 3;
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      try {
        return new String(file.contentsToByteArray());
      }
      catch (IOException e) {
        LOG.info(String.format("IOException while reading %s (attempt #%s)", file, attempt));
        if (attempt >= ATTEMPTS - 1) {
          throw e;
        }
      }
    }
    throw new AssertionError("Shouldn't get here. Couldn't read " + file);
  }

  /**
   * Sort files by Bazaar root
   *
   * @param virtualFiles files to sort
   * @return sorted files
   * @throws VcsException if non Bazaar files are passed
   */
  @NotNull
  public static Map<VirtualFile, List<VirtualFile>> sortFilesByBzrRoot(@NotNull Collection<VirtualFile> virtualFiles) throws VcsException {
    return sortFilesByBzrRoot(virtualFiles, false);
  }

  /**
   * Sort files by Bazaar root
   *
   * @param virtualFiles files to sort
   * @param ignoreNonBzr if true, non-Bazaar files are ignored
   * @return sorted files
   * @throws VcsException if non Bazaar files are passed when {@code ignoreNonBzr} is false
   */
  public static Map<VirtualFile, List<VirtualFile>> sortFilesByBzrRoot(Collection<VirtualFile> virtualFiles, boolean ignoreNonBzr)
    throws VcsException {
    Map<VirtualFile, List<VirtualFile>> result = new HashMap<VirtualFile, List<VirtualFile>>();
    for (VirtualFile file : virtualFiles) {
      final VirtualFile vcsRoot = bzrRootOrNull(file);
      if (vcsRoot == null) {
        if (ignoreNonBzr) {
          continue;
        }
        else {
          throw new VcsException("The file " + file.getPath() + " is not under Bazaar");
        }
      }
      List<VirtualFile> files = result.get(vcsRoot);
      if (files == null) {
        files = new ArrayList<VirtualFile>();
        result.put(vcsRoot, files);
      }
      files.add(file);
    }
    return result;
  }

  /**
   * Sort files by vcs root
   *
   * @param files files to sort.
   * @return the map from root to the files under the root
   * @throws VcsException if non Bazaar files are passed
   */
  public static Map<VirtualFile, List<FilePath>> sortFilePathsByBzrRoot(final Collection<FilePath> files) throws VcsException {
    return sortFilePathsByBzrRoot(files, false);
  }

  /**
   * Sort files by vcs root
   *
   * @param files files to sort.
   * @return the map from root to the files under the root
   */
  public static Map<VirtualFile, List<FilePath>> sortBzrFilePathsByBzrRoot(Collection<FilePath> files) {
    try {
      return sortFilePathsByBzrRoot(files, true);
    }
    catch (VcsException e) {
      throw new RuntimeException("Unexpected exception:", e);
    }
  }


  /**
   * Sort files by vcs root
   *
   * @param files        files to sort.
   * @param ignoreNonBzr if true, non-Bazaar files are ignored
   * @return the map from root to the files under the root
   * @throws VcsException if non git files are passed when {@code ignoreNonBzr} is false
   */
  @NotNull
  public static Map<VirtualFile, List<FilePath>> sortFilePathsByBzrRoot(@NotNull Collection<FilePath> files, boolean ignoreNonBzr)
    throws VcsException {
    Map<VirtualFile, List<FilePath>> rc = new HashMap<VirtualFile, List<FilePath>>();
    for (FilePath p : files) {
      VirtualFile root = getBzrRootOrNull(p);
      if (root == null) {
        if (ignoreNonBzr) {
          continue;
        }
        else {
          throw new VcsException("The file " + p.getPath() + " is not under Bazaar");
        }
      }
      List<FilePath> l = rc.get(root);
      if (l == null) {
        l = new ArrayList<FilePath>();
        rc.put(root, l);
      }
      l.add(p);
    }
    return rc;
  }

  /**
   * Parse UNIX timestamp as it is returned by Bazaar
   *
   * @param value a value to parse
   * @return timestamp as {@link Date} object
   */
  private static Date parseTimestamp(String value) throws ParseException {
    return COMMIT_DATE_FORMAT.parse(value);
  }

  /**
   * Parse UNIX timestamp returned from Bazaar and handle {@link NumberFormatException} if one happens: return new {@link Date} and
   * log the error properly.
   * In some cases git output gets corrupted and this method is intended to catch the reason, why.
   * @param value      Value to parse.
   * @param handler    Bazaar handler that was called to received the output.
   * @param bzrOutput  Bazaar output.
   * @return Parsed Date or <code>new Date</code> in the case of error.
   */
  public static Date parseTimestampWithNFEReport(String value, BzrHandler handler, String bzrOutput) {
    try {
      return parseTimestamp(value);
    } catch (ParseException e) {
      LOG.error("annotate(). NFE. Handler: " + handler + ". Output: " + bzrOutput, e);
      return  new Date();
    }
  }

  /**
   * Get Bazaar roots from content roots
   *
   * @param roots Bazaar content roots
   * @return a content root
   */
  public static Set<VirtualFile> bzrRootsForPaths(final Collection<VirtualFile> roots) {
    HashSet<VirtualFile> rc = new HashSet<VirtualFile>();
    for (VirtualFile root : roots) {
      VirtualFile f = root;
      do {
        if (f.findFileByRelativePath(DOT_BZR) != null) {
          rc.add(f);
          break;
        }
        f = f.getParent();
      }
      while (f != null);
    }
    return rc;
  }

  /**
   * Return a Bazaar root for the file path (the parent directory with ".bzr" subdirectory)
   *
   * @param filePath a file path
   * @return Bazaar root for the file
   * @throws IllegalArgumentException if the file is not under Bazaar
   * @throws VcsException             if the file is not under Bazaar
   *
   * @deprecated because uses the java.io.File.
   * @use BzrRepositoryManager#getRepositoryForFile().
   */
  public static VirtualFile getBzrRoot(@NotNull FilePath filePath) throws VcsException {
    VirtualFile root = getBzrRootOrNull(filePath);
    if (root != null) {
      return root;
    }
    throw new VcsException("The file " + filePath + " is not under Bazaar.");
  }

  /**
   * Return a Bazaar root for the file path (the parent directory with ".bzr" subdirectory)
   *
   * @param filePath a file path
   * @return Bazaar root for the file or null if the file is not under Bazaar
   *
   * @deprecated because uses the java.io.File.
   * @use BzrRepositoryManager#getRepositoryForFile().
   */
  @Deprecated
  @Nullable
  public static VirtualFile getBzrRootOrNull(@NotNull final FilePath filePath) {
    return getBzrRootOrNull(filePath.getIOFile());
  }

  public static boolean isBzrRoot(final File file) {
    return file != null && file.exists() && file.isDirectory() && new File(file, DOT_BZR).exists();
  }

  /**
   * @deprecated because uses the java.io.File.
   * @use BzrRepositoryManager#getRepositoryForFile().
   */
  @Deprecated
  @Nullable
  public static VirtualFile getBzrRootOrNull(final File file) {
    File root = file;
    while (root != null && (!root.exists() || !root.isDirectory() || !new File(root, DOT_BZR).exists())) {
      root = root.getParentFile();
    }
    return root == null ? null : LocalFileSystem.getInstance().findFileByIoFile(root);
  }

  /**
   * Return a Bazaar root for the file (the parent directory with ".bzr" subdirectory)
   *
   * @param file the file to check
   * @return Bazaar root for the file
   * @throws VcsException if the file is not under Bazaar
   *
   * @deprecated because uses the java.io.File.
   * @use BazaarRepositoryManager#getRepositoryForFile().
   */
  public static VirtualFile getBzrRoot(@NotNull final VirtualFile file) throws VcsException {
    final VirtualFile root = bzrRootOrNull(file);
    if (root != null) {
      return root;
    }
    else {
      throw new VcsException("The file " + file.getPath() + " is not under git.");
    }
  }

  /**
   * Return a Bazaar root for the file (the parent directory with ".bzr" subdirectory)
   *
   * @param file the file to check
   * @return Bazaar root for the file or null if the file is not not under Bazaar
   *
   * @deprecated because uses the java.io.File.
   * @use BzrRepositoryManager#getRepositoryForFile().
   */
  @Nullable
  public static VirtualFile bzrRootOrNull(final VirtualFile file) {
    if (file instanceof AbstractVcsVirtualFile) {
      return getBzrRootOrNull(VcsUtil.getFilePath(file.getPath()));
    }
    VirtualFile root = file;
    while (root != null) {
      if (root.findFileByRelativePath(DOT_BZR) != null) {
        return root;
      }
      root = root.getParent();
    }
    return root;
  }

  /**
   * Get Bazaar roots for the project. The method shows dialogs in the case when roots cannot be retrieved, so it should be called
   * from the event dispatch thread.
   *
   * @param project the project
   * @param vcs     the Bazaar Vcs
   * @return the list of the roots
   *
   * @deprecated because uses the java.io.File.
   * @use BzrRepositoryManager#getRepositoryForFile().
   */
  @NotNull
  public static List<VirtualFile> getBzrRoots(Project project, BzrVcs vcs) throws VcsException {
    final VirtualFile[] contentRoots = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs);
    if (contentRoots == null || contentRoots.length == 0) {
      throw new VcsException(BzrBundle.getString("repository.action.missing.roots.unconfigured.message"));
    }
    final List<VirtualFile> roots = new ArrayList<VirtualFile>(bzrRootsForPaths(Arrays.asList(contentRoots)));
    if (roots.size() == 0) {
      throw new VcsException(BzrBundle.getString("repository.action.missing.roots.misconfigured"));
    }
    Collections.sort(roots, VIRTUAL_FILE_COMPARATOR);
    return roots;
  }


  /**
   * Check if the virtual file under Bazaar
   *
   * @param vFile a virtual file
   * @return true if the file is under Bazaar
   */
  public static boolean isUnderBzr(final VirtualFile vFile) {
    return bzrRootOrNull(vFile) != null;
  }


  /**
   * Return committer name based on author name and committer name
   *
   * @param authorName    the name of author
   * @param committerName the name of committer
   * @return just a name if they are equal, or name that includes both author and committer
   */
  public static String adjustAuthorName(final String authorName, String committerName) {
    if (!authorName.equals(committerName)) {
      //noinspection HardCodedStringLiteral
      committerName = authorName + ", via " + committerName;
    }
    return committerName;
  }

  /**
   * Check if the file path is under Bazaar
   *
   * @param path the path
   * @return true if the file path is under Bazaar
   */
  public static boolean isUnderBzr(final FilePath path) {
    return getBzrRootOrNull(path) != null;
  }

  /**
   * Get git roots for the selected paths
   *
   * @param filePaths the context paths
   * @return a set of Bazaar roots
   */
  public static Set<VirtualFile> bzrRoots(final Collection<FilePath> filePaths) {
    HashSet<VirtualFile> rc = new HashSet<VirtualFile>();
    for (FilePath path : filePaths) {
      final VirtualFile root = getBzrRootOrNull(path);
      if (root != null) {
        rc.add(root);
      }
    }
    return rc;
  }

  /**
   * Get git time (UNIX time) basing on the date object
   *
   * @param time the time to convert
   * @return the time in Bazaar format
   */
  public static String bzrTime(Date time) {
    long t = time.getTime() / 1000;
    return Long.toString(t);
  }

  /**
   * Format revision number from long to 16-digit abbreviated revision
   *
   * @param rev the abbreviated revision number as long
   * @return the revision string
   */
  public static String formatLongRev(long rev) {
    return String.format("%015x%x", (rev >>> 4), rev & 0xF);
  }

  public static void getLocalCommittedChanges(final Project project,
                                              final VirtualFile root,
                                              final Consumer<BzrSimpleHandler> parametersSpecifier,
                                              final Consumer<BzrCommittedChangeList> consumer, boolean skipDiffsForMerge) throws VcsException {
    BzrSimpleHandler h = new BzrSimpleHandler(project, root, BzrCommand.LOG);
    h.setSilent(true);
    h.addParameters("--pretty=format:%x04%x01" + BzrChangeUtils.COMMITTED_CHANGELIST_FORMAT, "--name-status");
    parametersSpecifier.consume(h);

    String output = h.run();
    LOG.debug("getLocalCommittedChanges output: '" + output + "'");
    StringScanner s = new StringScanner(output);
    final StringBuilder sb = new StringBuilder();
    boolean firstStep = true;
    while (s.hasMoreData()) {
      final String line = s.line();
      final boolean lineIsAStart = line.startsWith("\u0004\u0001");
      if ((!firstStep) && lineIsAStart) {
        final StringScanner innerScanner = new StringScanner(sb.toString());
        sb.setLength(0);
        consumer.consume(BzrChangeUtils.parseChangeList(project, root, innerScanner, skipDiffsForMerge, h, false, false));
      }
      sb.append(lineIsAStart ? line.substring(2) : line).append('\n');
      firstStep = false;
    }
    if (sb.length() > 0) {
      final StringScanner innerScanner = new StringScanner(sb.toString());
      sb.setLength(0);
      consumer.consume(BzrChangeUtils.parseChangeList(project, root, innerScanner, skipDiffsForMerge, h, false, false));
    }
    if (s.hasMoreData()) {
      throw new IllegalStateException("More input is avaialble: " + s.line());
    }
  }

  public static List<BzrCommittedChangeList> getLocalCommittedChanges(final Project project,
                                                                   final VirtualFile root,
                                                                   final Consumer<BzrSimpleHandler> parametersSpecifier)
    throws VcsException {
    final List<BzrCommittedChangeList> rc = new ArrayList<BzrCommittedChangeList>();

    getLocalCommittedChanges(project, root, parametersSpecifier, new Consumer<BzrCommittedChangeList>() {
      public void consume(BzrCommittedChangeList committedChangeList) {
        rc.add(committedChangeList);
      }
    }, false);

    return rc;
  }

  /**
   * <p>Unescape path returned by Bazaar.</p>
   *
   * @param path a path to unescape
   * @return unescaped path ready to be searched in the VFS or file system.
   * @throws com.intellij.openapi.vcs.VcsException if the path in invalid
   */
  @NotNull
  public static String unescapePath(@NotNull String path) throws VcsException {
    final String QUOTE = "\"";
    if (path.startsWith(QUOTE) && path.endsWith(QUOTE)) {
      path = path.substring(1, path.length() - 1);
    }

    final int l = path.length();
    StringBuilder rc = new StringBuilder(l);
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '\\') {
        //noinspection AssignmentToForLoopParameter
        i++;
        if (i >= l) {
          throw new VcsException("Unterminated escape sequence in the path: " + path);
        }
        final char e = path.charAt(i);
        switch (e) {
          case '\\':
            rc.append('\\');
            break;
          case 't':
            rc.append('\t');
            break;
          case 'n':
            rc.append('\n');
            break;
          case '"':
            rc.append('"');
            break;
          default:
            if (VcsFileUtil.isOctal(e)) {
              // collect sequence of characters as a byte array.
              // count bytes first
              int n = 0;
              for (int j = i; j < l;) {
                if (VcsFileUtil.isOctal(path.charAt(j))) {
                  n++;
                  for (int k = 0; k < 3 && j < l && VcsFileUtil.isOctal(path.charAt(j)); k++) {
                    //noinspection AssignmentToForLoopParameter
                    j++;
                  }
                }
                if (j + 1 >= l || path.charAt(j) != '\\' || !VcsFileUtil.isOctal(path.charAt(j + 1))) {
                  break;
                }
                //noinspection AssignmentToForLoopParameter
                j++;
              }
              // convert to byte array
              byte[] b = new byte[n];
              n = 0;
              while (i < l) {
                if (VcsFileUtil.isOctal(path.charAt(i))) {
                  int code = 0;
                  for (int k = 0; k < 3 && i < l && VcsFileUtil.isOctal(path.charAt(i)); k++) {
                    code = code * 8 + (path.charAt(i) - '0');
                    //noinspection AssignmentToForLoopParameter
                    i++;
                  }
                  b[n++] = (byte)code;
                }
                if (i + 1 >= l || path.charAt(i) != '\\' || !VcsFileUtil.isOctal(path.charAt(i + 1))) {
                  break;
                }
                //noinspection AssignmentToForLoopParameter
                i++;
              }
              assert n == b.length;
              // add them to string
              final String encoding = BzrConfigUtil.getFileNameEncoding();
              try {
                rc.append(new String(b, encoding));
              }
              catch (UnsupportedEncodingException e1) {
                throw new IllegalStateException("The file name encoding is unsuported: " + encoding);
              }
            }
            else {
              throw new VcsException("Unknown escape sequence '\\" + path.charAt(i) + "' in the path: " + path);
            }
        }
      }
      else {
        rc.append(c);
      }
    }
    return rc.toString();
  }
  
  public static boolean justOneBzrRepository(Project project) {
    if (project.isDisposed()) {
      return true;
    }
    BzrRepositoryManager manager = getRepositoryManager(project);
    return !manager.moreThanOneRoot();
  }


  @Nullable
  public static BzrRemote findRemoteByName(@NotNull BzrRepository repository, @Nullable String name) {
    if (name == null) {
      return null;
    }
    for (BzrRemote remote : repository.getRemotes()) {
      if (remote.getName().equals(name)) {
        return remote;
      }
    }
    return null;
  }

  /**
   * @deprecated Calls Bazaar for tracked info, use {@link bazaar4idea.repo.BzrRepository#getBranchTrackInfos()} instead.
   */
  @Nullable
  @Deprecated
  public static Pair<BzrRemote, BzrRemoteBranch> findMatchingRemoteBranch(BzrRepository repository, BzrLocalBranch branch)
    throws VcsException {
    /*
    from man git-push:
    git push
               Works like git push <remote>, where <remote> is the current branch's remote (or origin, if no
               remote is configured for the current branch).

     */
    String remoteName = BzrBranchUtil.getTrackedRemoteName(repository.getProject(), repository.getRoot(), branch.getName());
    BzrRemote remote;
    if (remoteName == null) {
      remote = findOrigin(repository.getRemotes());
    } else {
      remote = findRemoteByName(repository, remoteName);
    }
    if (remote == null) {
      return null;
    }

    for (BzrRemoteBranch remoteBranch : repository.getBranches().getRemoteBranches()) {
      if (remoteBranch.getName().equals(remote.getName() + "/" + branch.getName())) {
        return Pair.create(remote, remoteBranch);
      }
    }
    return null;
  }

  @Nullable
  private static BzrRemote findOrigin(Collection<BzrRemote> remotes) {
    for (BzrRemote remote : remotes) {
      if (remote.getName().equals("origin")) {
        return remote;
      }
    }
    return null;
  }

  public static boolean repoContainsRemoteBranch(@NotNull BzrRepository repository, @NotNull BzrRemoteBranch dest) {
    return repository.getBranches().getRemoteBranches().contains(dest);
  }

  @NotNull
  public static Collection<VirtualFile> getRootsFromRepositories(@NotNull Collection<BzrRepository> repositories) {
    Collection<VirtualFile> roots = new ArrayList<VirtualFile>(repositories.size());
    for (BzrRepository repository : repositories) {
      roots.add(repository.getRoot());
    }
    return roots;
  }

  @NotNull
  public static Collection<BzrRepository> getRepositoriesFromRoots(@NotNull BzrRepositoryManager repositoryManager,
                                                                   @NotNull Collection<VirtualFile> roots) {
    Collection<BzrRepository> repositories = new ArrayList<BzrRepository>(roots.size());
    for (VirtualFile root : roots) {
      BzrRepository repo = repositoryManager.getRepositoryForRoot(root);
      if (repo == null) {
        LOG.error("Repository not found for root " + root);
      }
      else {
        repositories.add(repo);
      }
    }
    return repositories;
  }

  /**
   * Returns absolute paths which have changed remotely comparing to the current branch, i.e. performs
   * <code>git diff --name-only master..origin/master</code>
   */
  @NotNull
  public static Collection<String> getPathsDiffBetweenRefs(@NotNull Bzr bzr, @NotNull BzrRepository repository,
                                                           @NotNull String beforeRef, @NotNull String afterRef) throws VcsException {
    List<String> parameters = Arrays.asList("--name-only", "--pretty=format:");
    String range = beforeRef + ".." + afterRef;
    BzrCommandResult result = bzr.diff(repository, parameters, range);
    if (!result.success()) {
      LOG.info(String.format("Couldn't get diff in range [%s] for repository [%s]", range, repository.toLogString()));
      return Collections.emptyList();
    }

    final Collection<String> remoteChanges = new HashSet<String>();
    for (StringScanner s = new StringScanner(result.getOutputAsJoinedString()); s.hasMoreData(); ) {
      final String relative = s.line();
      if (StringUtil.isEmptyOrSpaces(relative)) {
        continue;
      }
      final String path = repository.getRoot().getPath() + "/" + unescapePath(relative);
      remoteChanges.add(FilePathsHelper.convertPath(path));
    }
    return remoteChanges;
  }

  @NotNull
  public static BzrRepositoryManager getRepositoryManager(@NotNull Project project) {
    return ServiceManager.getService(project, BzrRepositoryManager.class);
  }

  @Nullable
  public static BzrRepository getRepositoryForRootOrLogError(@NotNull Project project, @NotNull VirtualFile root) {
    BzrRepositoryManager manager = getRepositoryManager(project);
    BzrRepository repository = manager.getRepositoryForRoot(root);
    if (repository == null) {
      LOG.error("Repository is null for root " + root);
    }
    return repository;
  }

  @NotNull
  public static String getPrintableRemotes(@NotNull Collection<BzrRemote> remotes) {
    return StringUtil.join(remotes, new Function<BzrRemote, String>() {
      @Override
      public String fun(BzrRemote remote) {
        return remote.getName() + ": [" + StringUtil.join(remote.getUrls(), ", ") + "]";
      }
    }, "\n");
  }

  @NotNull
  public static String getShortHash(@NotNull String hash) {
    if (hash.length() == 0) return "";
    if (hash.length() == 40) return hash.substring(0, SHORT_HASH_LENGTH);
    if (hash.length() > 40)  // revision string encoded with date too
    {
      return hash.substring(hash.indexOf("[") + 1, SHORT_HASH_LENGTH);
    }
    return hash;
  }

  @NotNull
  public static String fileOrFolder(@NotNull VirtualFile file) {
    if (file.isDirectory()) {
      return "Folder";
    }
    else {
      return "File";
    }
  }

  /**
   * Show changes made in the specified revision.
   *
   * @param project     the project
   * @param revision    the revision number
   * @param file        the file affected by the revision
   * @param local       pass true to let the diff be editable, i.e. making the revision "at the right" be a local (current) revision.
   *                    pass false to let both sides of the diff be non-editable.
   * @param revertable  pass true to let "Revert" action be active.
   */
  public static void showSubmittedFiles(final Project project, final String revision, final VirtualFile file,
                                        final boolean local, final boolean revertable) {
    new Task.Backgroundable(project, BzrBundle.message("changes.retrieving", revision)) {
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        try {
          VirtualFile vcsRoot = getBzrRoot(file);
          final CommittedChangeList changeList = BzrChangeUtils.getRevisionChanges(project, vcsRoot, revision, true, local, revertable);
          if (changeList != null) {
            UIUtil.invokeLaterIfNeeded(new Runnable() {
              public void run() {
                AbstractVcsHelper.getInstance(project).showChangesListBrowser(changeList,
                                                                              BzrBundle.message("paths.affected.title", revision));
              }
            });
          }
        }
        catch (final VcsException e) {
          UIUtil.invokeLaterIfNeeded(new Runnable() {
            public void run() {
              BzrUIUtil.showOperationError(project, e, "bzr show");
            }
          });
        }
      }
    }.queue();
  }


  /**
   * Returns the tracking information (remote and the name of the remote branch), or null if we are not on a branch.
   */
  @Nullable
  public static BzrBranchTrackInfo getTrackInfoForCurrentBranch(@NotNull BzrRepository repository) {
    BzrLocalBranch currentBranch = repository.getCurrentBranch();
    if (currentBranch == null) {
      return null;
    }
    return BzrBranchUtil.getTrackInfoForBranch(repository, currentBranch);
  }

  @NotNull
  public static Collection<BzrRepository> getRepositoriesForFiles(@NotNull Project project, @NotNull Collection<VirtualFile> files) {
    final BzrRepositoryManager manager = getRepositoryManager(project);
    com.google.common.base.Function<VirtualFile,BzrRepository> ROOT_TO_REPO =
      new com.google.common.base.Function<VirtualFile, BzrRepository>() {
        @Override
        public BzrRepository apply(@Nullable VirtualFile root) {
          return root != null ? manager.getRepositoryForRoot(root) : null;
        }
      };
    return Collections2.filter(Collections2.transform(sortFilesByBzrRootsIgnoringOthers(files).keySet(), ROOT_TO_REPO),
                               Predicates.notNull());
  }

  @NotNull
  public static Map<VirtualFile, List<VirtualFile>> sortFilesByBzrRootsIgnoringOthers(@NotNull Collection<VirtualFile> files) {
    try {
      return sortFilesByBzrRoot(files, true);
    }
    catch (VcsException e) {
      LOG.error("Should never happen, since we passed 'ignore non-Bazaar' parameter", e);
      return Collections.emptyMap();
    }
  }

  /**
   * bzr status --short --versioned
   * @return true if there is anything in the unstaged/staging area, false if the unstaged/staging area is empty.
   * @param staged if true checks the staging area, if false checks unstaged files.
   * @param project
   * @param root
   */
  public static boolean hasLocalChanges(boolean staged, Project project, VirtualFile root) throws VcsException {
    final BzrSimpleHandler handler = new BzrSimpleHandler(project, root, BzrCommand.STATUS);
    handler.addParameters("--short", "--versioned");
    //if (staged) {
    //  handler.addParameters("--cached");
    //}
    handler.setStdoutSuppressed(true);
    handler.setStderrSuppressed(true);
    //handler.setSilent(true);
    final String output = handler.run();
    return !output.trim().isEmpty();
  }

}
