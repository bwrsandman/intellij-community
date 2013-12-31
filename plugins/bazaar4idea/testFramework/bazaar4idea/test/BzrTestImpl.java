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
package bazaar4idea.test;

import bazaar4idea.BzrCommit;
import bazaar4idea.commands.*;
import bazaar4idea.push.BzrPushSpec;
import bazaar4idea.repo.BzrRepository;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static com.intellij.openapi.util.text.StringUtil.join;
import static bazaar4idea.test.BzrExecutor.cd;
import static java.lang.String.format;

/**
 * @author Kirill Likhodedov
 */
public class BzrTestImpl implements Bzr {

  @NotNull
  @Override
  public BzrCommandResult init(@NotNull Project project, @NotNull VirtualFile root, @NotNull BzrLineHandlerListener... listeners) {
    return execute(root.getPath(), "init", listeners);
  }

  @NotNull
  @Override
  public Set<VirtualFile> untrackedFiles(@NotNull Project project, @NotNull VirtualFile root, @Nullable Collection<VirtualFile> files)
    throws VcsException {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public Collection<VirtualFile> untrackedFilesNoChunk(@NotNull Project project,
                                                       @NotNull VirtualFile root,
                                                       @Nullable List<String> relativePaths) throws VcsException {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public BzrCommandResult clone(@NotNull Project project,
                                @NotNull File parentDirectory,
                                @NotNull String url,
                                @NotNull String clonedDirectoryName, @NotNull BzrLineHandlerListener... progressListeners) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public BzrCommandResult config(@NotNull BzrRepository repository, String... params) {
    cd(repository);
    String output = BzrExecutor.bzr("config " + join(params, " "));
    int exitCode = output.trim().isEmpty() ? 1 : 0;
    return new BzrCommandResult(!output.contains("fatal") && exitCode == 0, exitCode, Collections.<String>emptyList(),
                                Arrays.asList(StringUtil.splitByLines(output)), null);
  }

  @NotNull
  @Override
  public BzrCommandResult diff(@NotNull BzrRepository repository, @NotNull List<String> parameters, @NotNull String range) {
    return execute(repository, format("diff %s %s", join(parameters, " "), range));
  }

  @NotNull
  @Override
  public BzrCommandResult stashSave(@NotNull BzrRepository repository, @NotNull String message) {
    return execute(repository, "stash save " + message);
  }

  @NotNull
  @Override
  public BzrCommandResult stashPop(@NotNull BzrRepository repository, @NotNull BzrLineHandlerListener... listeners) {
    return execute(repository, "stash pop");
  }

  @NotNull
  @Override
  public List<BzrCommit> history(@NotNull BzrRepository repository, @NotNull String range) {
    return Collections.emptyList();
  }

  @NotNull
  @Override
  public BzrCommandResult merge(@NotNull BzrRepository repository, @NotNull String branchToMerge, @Nullable List<String> additionalParams,
                                @NotNull BzrLineHandlerListener... listeners) {
    String addParams = additionalParams == null ? "" : join(additionalParams, " ");
    return execute(repository, format("merge %s %s", addParams, branchToMerge), listeners);
  }

  @NotNull
  @Override
  public BzrCommandResult checkout(@NotNull BzrRepository repository, @NotNull String reference, @Nullable String newBranch, boolean force,
                                   @NotNull BzrLineHandlerListener... listeners) {
    return execute(repository, format("checkout %s %s %s",
                                      force ? "--force" : "",
                                      newBranch != null ? " -b " + newBranch : "", reference), listeners);
  }

  @NotNull
  @Override
  public BzrCommandResult checkoutNewBranch(@NotNull BzrRepository repository, @NotNull String branchName,
                                            @Nullable BzrLineHandlerListener listener) {
    return execute(repository, "checkout -b " + branchName, listener);
  }

  @NotNull
  @Override
  public BzrCommandResult branchDelete(@NotNull BzrRepository repository, @NotNull String branchName, boolean force,
                                       @NotNull BzrLineHandlerListener... listeners) {
    return execute(repository, format("branch %s %s", force ? "-D" : "-d", branchName), listeners);
  }

  @NotNull
  @Override
  public BzrCommandResult branchContains(@NotNull BzrRepository repository, @NotNull String commit) {
    return execute(repository, "branch --contains " + commit);
  }

  @NotNull
  @Override
  public BzrCommandResult branchCreate(@NotNull BzrRepository repository, @NotNull String branchName) {
    return execute(repository, "branch " + branchName);
  }

  @NotNull
  @Override
  public BzrCommandResult resetHard(@NotNull BzrRepository repository, @NotNull String revision) {
    return execute(repository, "reset --hard " + revision);
  }

  @NotNull
  @Override
  public BzrCommandResult resetMerge(@NotNull BzrRepository repository, @Nullable String revision) {
    return execute(repository, "reset --merge " + revision);
  }

  @NotNull
  @Override
  public BzrCommandResult tip(@NotNull BzrRepository repository, @NotNull String branchName) {
    return execute(repository, "rev-list -1 " + branchName);
  }

  @NotNull
  @Override
  public BzrCommandResult push(@NotNull BzrRepository repository, @NotNull String remote, @NotNull String url, @NotNull String spec,
                               boolean updateTracking, @NotNull BzrLineHandlerListener... listeners) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public BzrCommandResult push(@NotNull BzrRepository repository, @NotNull String remote, @NotNull String url, @NotNull String spec,
                               @NotNull BzrLineHandlerListener... listeners) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public BzrCommandResult push(@NotNull BzrRepository repository,
                               @NotNull BzrPushSpec spec, @NotNull String url, @NotNull BzrLineHandlerListener... listeners) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public BzrCommandResult show(@NotNull BzrRepository repository, @NotNull String... params) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public BzrCommandResult cherryPick(@NotNull BzrRepository repository,
                                     @NotNull String hash,
                                     boolean autoCommit,
                                     @NotNull BzrLineHandlerListener... listeners) {
    return execute(repository, format("cherry-pick -x %s %s", autoCommit ? "" : "-n", hash), listeners);
  }

  @NotNull
  @Override
  public BzrCommandResult fetch(@NotNull BzrRepository repository, @NotNull String url, @NotNull String remote, String... params) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public BzrCommandResult getUnmergedFiles(@NotNull BzrRepository repository) {
    return execute(repository, "ls-files --unmerged");
  }

  @NotNull
  @Override
  public BzrCommandResult createNewTag(@NotNull BzrRepository repository,
                                       @NotNull String tagName,
                                       @Nullable BzrLineHandlerListener listener,
                                       @NotNull String reference) {
    throw new UnsupportedOperationException();
  }

  private static BzrCommandResult commandResult(String output) {
    List<String> err = new ArrayList<String>();
    List<String> out = new ArrayList<String>();
    for (String line : output.split("\n")) {
      if (isError(line)) {
        err.add(line);
      }
      else {
        out.add(line);
      }
    }
    boolean success = err.isEmpty();
    return new BzrCommandResult(success, 0, err, out, null);
  }

  private static boolean isError(String s) {
    // we don't want to make that method public, since it is reused only in the test.
    try {
      Method m = BzrImpl.class.getDeclaredMethod("isError", String.class);
      m.setAccessible(true);
      return (Boolean) m.invoke(null, s);
    }
    catch (NoSuchMethodException e) {
      e.printStackTrace();
    }
    catch (InvocationTargetException e) {
      e.printStackTrace();
    }
    catch (IllegalAccessException e) {
      e.printStackTrace();
    }
    return true;
  }

  private static void feedOutput(String output, BzrLineHandlerListener... listeners) {
    for (BzrLineHandlerListener listener : listeners) {
      String[] split = output.split("\n");
      for (String line : split) {
        listener.onLineAvailable(line, ProcessOutputTypes.STDERR);
      }
    }
  }

  private static BzrCommandResult execute(BzrRepository repository, String operation, BzrLineHandlerListener... listeners) {
    return execute(repository.getRoot().getPath(), operation, listeners);
  }

  private static BzrCommandResult execute(String workingDir, String operation, BzrLineHandlerListener... listeners) {
    cd(workingDir);
    String out = BzrExecutor.bzr(operation);
    feedOutput(out, listeners);
    return commandResult(out);
  }

}
