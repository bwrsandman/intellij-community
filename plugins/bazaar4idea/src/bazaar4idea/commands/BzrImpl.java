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
package bazaar4idea.commands;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrCommit;
import bazaar4idea.BzrExecutionException;
import bazaar4idea.history.BzrHistoryUtils;
import bazaar4idea.push.BzrPushSpec;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.util.BzrFileUtils;import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsFileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Easy-to-use wrapper of common native Bazaar commands.
 * Most of them return result as {@link BzrCommandResult}.
 *
 * @author Kirill Likhodedov
 */
public class BzrImpl implements Bzr {

  private final Logger LOG = Logger.getInstance(Bzr.class);

  public BzrImpl() {
  }

  /**
   * Calls 'bzr init' on the specified directory.
   */
  @NotNull
  @Override
  public BzrCommandResult init(@NotNull Project project, @NotNull VirtualFile root, @NotNull BzrLineHandlerListener... listeners) {
    BzrLineHandler handler = new BzrLineHandler(project, root, BzrCommand.INIT);
    for (BzrLineHandlerListener listener : listeners) {
      handler.addLineListener(listener);
    }
    handler.setSilent(false);
    return run(handler);
  }

  /**
   * <p>Queries Bazaar for the unversioned files in the given paths. </p>
   * <p>Ignored files are left ignored, i. e. no information is returned about them (thus this method may also be used as a
   *    ignored files checker.</p>
   *
   * @param files files that are to be checked for the unversioned files among them.
   *              <b>Pass <code>null</code> to query the whole repository.</b>
   * @return Unversioned not ignored files from the given scope.
   */
  @Override
  @NotNull
  public Set<VirtualFile> untrackedFiles(@NotNull Project project, @NotNull VirtualFile root,
                                         @Nullable Collection<VirtualFile> files) throws VcsException {
    final Set<VirtualFile> untrackedFiles = new HashSet<VirtualFile>();

    if (files == null) {
      untrackedFiles.addAll(untrackedFilesNoChunk(project, root, null));
    }
    else {
      for (List<String> relativePaths : VcsFileUtil.chunkFiles(root, files)) {
        untrackedFiles.addAll(untrackedFilesNoChunk(project, root, relativePaths));
      }
    }

    return untrackedFiles;
  }

  // relativePaths are guaranteed to fit into command line length limitations.
  @Override
  @NotNull
  public Collection<VirtualFile> untrackedFilesNoChunk(@NotNull Project project,
                                                       @NotNull VirtualFile root,
                                                       @Nullable List<String> relativePaths)
    throws VcsException {
    final Set<String> untrackedFileNames = new HashSet<String>();
    final Set<VirtualFile> untrackedFiles = new HashSet<VirtualFile>();
    BzrSimpleHandler handler = new BzrSimpleHandler(project, root, BzrCommand.LS);
    handler.setSilent(true);
    handler.addParameters("--unknown", "--null", "--recursive", "--from-root");
    // Bazaar doesn't support listing files
    //if (relativePaths != null) {
    //  handler.addParameters(relativePaths);
    //}

    final String output = handler.run();
    if (StringUtil.isEmptyOrSpaces(output)) {
      return untrackedFiles;
    }

    for (String relPath : output.split("\u0000")) {
      untrackedFileNames.add(relPath);
    }
    if (relativePaths != null) {
      // We filter out paths we from relativePaths
      for (String relPath : relativePaths) {
        VirtualFile f = root.findFileByRelativePath(relPath);
        if (f == null) {
          // files was created on disk, but VirtualFile hasn't yet been created,
          // when the BzrChangeProvider has already been requested about changes.
          LOG.info(String.format("VirtualFile for path [%s] is null", relPath));
          continue;
        }
        if (BzrFileUtils.recursiveUpContains(untrackedFileNames, f, root.getPath())) {
          untrackedFiles.add(f);
        }
      }
    } else {
      for (String relPath: untrackedFileNames) {
        VirtualFile f = root.findFileByRelativePath(relPath);
        if (f == null) {
          // files was created on disk, but VirtualFile hasn't yet been created,
          // when the GitChangeProvider has already been requested about changes.
          LOG.info(String.format("VirtualFile for path [%s] is null", relPath));
        } else {
          untrackedFiles.add(f);
        }
      }
    }


    return untrackedFiles;
  }

  
  @Override
  @NotNull
  public BzrCommandResult clone(@NotNull Project project, @NotNull File parentDirectory, @NotNull String url,
                                @NotNull String clonedDirectoryName, @NotNull BzrLineHandlerListener... listeners) {
    BzrLineHandlerPasswordRequestAware handler = new BzrLineHandlerPasswordRequestAware(project, parentDirectory, BzrCommand.BRANCH);
    handler.setUrl(url);
    handler.addParameters(url, clonedDirectoryName);
    addListeners(handler, listeners);
    return run(handler);
  }

  @NotNull
  @Override
  public BzrCommandResult config(@NotNull BzrRepository repository, String... params) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.CONFIG);
    h.addParameters(params);
    return run(h);
  }

  @NotNull
  @Override
  public BzrCommandResult diff(@NotNull BzrRepository repository, @NotNull List<String> parameters, @NotNull String range) {
    final BzrLineHandler handler = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.DIFF);
    handler.addParameters(parameters);
    handler.addParameters(range);
    handler.setStdoutSuppressed(true);
    handler.setStderrSuppressed(true);
    //handler.setSilent(true);
    return run(handler);
  }

  @NotNull
  @Override
  public BzrCommandResult stashSave(@NotNull BzrRepository repository, @NotNull String message) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.STASH);
    h.addParameters("save");
    h.addParameters(message);
    return run(h);
  }

  @NotNull
  @Override
  public BzrCommandResult stashPop(@NotNull BzrRepository repository, @NotNull BzrLineHandlerListener... listeners) {
    final BzrLineHandler handler = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.STASH);
    handler.addParameters("pop");
    addListeners(handler, listeners);
    return run(handler);
  }

  @NotNull
  @Override
  public List<BzrCommit> history(@NotNull BzrRepository repository, @NotNull String range) {
    try {
      return BzrHistoryUtils.history(repository.getProject(), repository.getRoot(), range);
    }
    catch (VcsException e) {
      // this is critical, because we need to show the list of unmerged commits, and it shouldn't happen => inform user and developer
      throw new BzrExecutionException("Couldn't get [bzr log " + range + "] on repository [" + repository.getRoot() + "]", e);
    }
  }

  @Override
  @NotNull
  public BzrCommandResult merge(@NotNull BzrRepository repository, @NotNull String branchToMerge,
                                @Nullable List<String> additionalParams, @NotNull BzrLineHandlerListener... listeners) {
    final BzrLineHandler mergeHandler = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.MERGE);
    mergeHandler.setSilent(false);
    mergeHandler.addParameters(branchToMerge);
    if (additionalParams != null) {
      mergeHandler.addParameters(additionalParams);
    }
    for (BzrLineHandlerListener listener : listeners) {
      mergeHandler.addLineListener(listener);
    }
    return run(mergeHandler);
  }


  /**
   * {@code git checkout &lt;reference&gt;} <br/>
   * {@code git checkout -b &lt;newBranch&gt; &lt;reference&gt;}
   */
  @NotNull
  @Override
  public BzrCommandResult checkout(@NotNull BzrRepository repository,
                                          @NotNull String reference,
                                          @Nullable String newBranch,
                                          boolean force,
                                          @NotNull BzrLineHandlerListener... listeners) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.CHECKOUT);
    h.setSilent(false);
    if (force) {
      h.addParameters("--force");
    }
    if (newBranch == null) { // simply checkout
      h.addParameters(reference);
    } 
    else { // checkout reference as new branch
      h.addParameters("-b", newBranch, reference);
    }
    for (BzrLineHandlerListener listener : listeners) {
      h.addLineListener(listener);
    }
    return run(h);
  }

  /**
   * {@code git checkout -b &lt;branchName&gt;}
   */
  @NotNull
  @Override
  public BzrCommandResult checkoutNewBranch(@NotNull BzrRepository repository, @NotNull String branchName,
                                                   @Nullable BzrLineHandlerListener listener) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.CHECKOUT.readLockingCommand());
    h.setSilent(false);
    h.addParameters("-b");
    h.addParameters(branchName);
    if (listener != null) {
      h.addLineListener(listener);
    }
    return run(h);
  }

  @NotNull
  @Override
  public BzrCommandResult createNewTag(@NotNull BzrRepository repository, @NotNull String tagName,
                                       @Nullable BzrLineHandlerListener listener, @NotNull String reference) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.TAG);
    h.setSilent(false);
    h.addParameters(tagName);
    if (!reference.isEmpty()) {
      h.addParameters(reference);
    }
    if (listener != null) {
      h.addLineListener(listener);
    }
    return run(h);
  }

  /**
   * {@code git branch -d <reference>} or {@code git branch -D <reference>}
   */
  @NotNull
  @Override
  public BzrCommandResult branchDelete(@NotNull BzrRepository repository,
                                              @NotNull String branchName,
                                              boolean force,
                                              @NotNull BzrLineHandlerListener... listeners) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.BRANCH);
    h.setSilent(false);
    h.addParameters(force ? "-D" : "-d");
    h.addParameters(branchName);
    for (BzrLineHandlerListener listener : listeners) {
      h.addLineListener(listener);
    }
    return run(h);
  }

  /**
   * Get branches containing the commit.
   * {@code git branch --contains <commit>}
   */
  @Override
  @NotNull
  public BzrCommandResult branchContains(@NotNull BzrRepository repository, @NotNull String commit) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.BRANCH);
    h.addParameters("--contains", commit);
    return run(h);
  }

  /**
   * Create branch without checking it out.
   * {@code git branch <branchName>}
   */
  @Override
  @NotNull
  public BzrCommandResult branchCreate(@NotNull BzrRepository repository, @NotNull String branchName) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.BRANCH);
    h.addParameters(branchName);
    return run(h);
  }

  @Override
  @NotNull
  public BzrCommandResult resetHard(@NotNull BzrRepository repository, @NotNull String revision) {
    final BzrLineHandler handler = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.RESET);
    handler.addParameters("--hard", revision);
    return run(handler);
  }

  @Override
  @NotNull
  public BzrCommandResult resetMerge(@NotNull BzrRepository repository, @Nullable String revision) {
    final BzrLineHandler handler = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.RESET);
    handler.addParameters("--merge");
    if (revision != null) {
      handler.addParameters(revision);
    }
    return run(handler);
  }

  /**
   * Returns the last (tip) commit on the given branch.<br/>
   * {@code git rev-list -1 <branchName>}
   */
  @NotNull
  @Override
  public BzrCommandResult tip(@NotNull BzrRepository repository, @NotNull String branchName) {
    final BzrLineHandler h = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.VERSION_INFO);
    h.addParameters(branchName);
    return run(h);
  }

  @Override
  @NotNull
  public BzrCommandResult push(@NotNull BzrRepository repository, @NotNull String remote, @NotNull String url, @NotNull String spec,
                               boolean updateTracking, @NotNull BzrLineHandlerListener... listeners) {
    final BzrLineHandlerPasswordRequestAware h = new BzrLineHandlerPasswordRequestAware(repository.getProject(), repository.getRoot(),
                                                                                        BzrCommand.PUSH);
    h.setUrl(url);
    h.setSilent(false);
    addListeners(h, listeners);
    h.addProgressParameter();
    h.addParameters(remote);
    h.addParameters(spec);
    if (updateTracking) {
      h.addParameters("--set-upstream");
    }
    return run(h);
  }

  @Override
  @NotNull
  public BzrCommandResult push(@NotNull BzrRepository repository, @NotNull String remote, @NotNull String url, @NotNull String spec,
                               @NotNull BzrLineHandlerListener... listeners) {
    return push(repository, remote, url, spec, false, listeners);
  }

  @Override
  @NotNull
  public BzrCommandResult push(@NotNull BzrRepository repository, @NotNull BzrPushSpec pushSpec, @NotNull String url,
                               @NotNull BzrLineHandlerListener... listeners) {
    BzrRemote remote = pushSpec.getRemote();
    BzrBranch remoteBranch = pushSpec.getDest();
    String destination = remoteBranch.getName().replaceFirst(remote.getName() + "/", "");
    return push(repository, remote.getName(), url, pushSpec.getSource().getName() + ":" + destination, listeners);
  }

  @NotNull
  @Override
  public BzrCommandResult show(@NotNull BzrRepository repository, @NotNull String... params) {
    final BzrLineHandler handler = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.CAT);
    handler.addParameters(params);
    return run(handler);
  }

  @NotNull
  @Override
  public BzrCommandResult getUnmergedFiles(@NotNull BzrRepository repository) {
    BzrLineHandler handler = new BzrLineHandler(repository.getProject(), repository.getRoot(), BzrCommand.LS);
    handler.addParameters("--unmerged", "--from-root", "--recursive");
    handler.setSilent(true);
    return run(handler);
  }

  /**
   * Fetch remote branch
   * {@code git fetch <remote> <params>}
   */
  @Override
  @NotNull
  public BzrCommandResult fetch(@NotNull BzrRepository repository, @NotNull String url, @NotNull String remote, String... params) {
    final BzrLineHandlerPasswordRequestAware h =
      new BzrLineHandlerPasswordRequestAware(repository.getProject(), repository.getRoot(), BzrCommand.FETCH);
    h.setUrl(url);
    h.addParameters(remote);
    h.addParameters(params);
    h.addProgressParameter();
    return run(h);
  }

  private static void addListeners(@NotNull BzrLineHandler handler, @NotNull BzrLineHandlerListener... listeners) {
    for (BzrLineHandlerListener listener : listeners) {
      handler.addLineListener(listener);
    }
  }

  /**
   * Runs the given {@link BzrLineHandler} in the current thread and returns the {@link BzrCommandResult}.
   */
  private static BzrCommandResult run(@NotNull BzrLineHandler handler) {
    final List<String> errorOutput = new ArrayList<String>();
    final List<String> output = new ArrayList<String>();
    final AtomicInteger exitCode = new AtomicInteger();
    final AtomicBoolean startFailed = new AtomicBoolean();
    final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();
    
    handler.addLineListener(new BzrLineHandlerListener() {
      @Override public void onLineAvailable(String line, Key outputType) {
        if (isError(line)) {
          errorOutput.add(line);
        } else {
          output.add(line);
        }
      }

      @Override public void processTerminated(int code) {
        exitCode.set(code);
      }

      @Override public void startFailed(Throwable t) {
        startFailed.set(true);
        errorOutput.add("Failed to start Bazaar process");
        exception.set(t);
      }
    });
    
    handler.runInCurrentThread(null);

    if (handler instanceof BzrLineHandlerPasswordRequestAware && ((BzrLineHandlerPasswordRequestAware)handler).hadAuthRequest()) {
      errorOutput.add("Authentication failed");
    }

    final boolean success = !startFailed.get() && errorOutput.isEmpty() &&
                            (handler.isIgnoredErrorCode(exitCode.get()) || exitCode.get() == 0);
    return new BzrCommandResult(success, exitCode.get(), errorOutput, output, null);
  }
  
  /**
   * Check if the line looks line an error message
   */
  private static boolean isError(String text) {
    for (String indicator : ERROR_INDICATORS) {
      if (text.startsWith(indicator.toLowerCase())) {
        return true;
      }
    }
    return false;
  }

  // could be upper-cased, so should check case-insensitively
  public static final String[] ERROR_INDICATORS = {
    "error", "remote: error", "fatal",
    "Cannot apply", "Could not", "Interactive rebase already started", "refusing to pull", "cannot rebase:", "conflict",
    "unable"
  };

}
