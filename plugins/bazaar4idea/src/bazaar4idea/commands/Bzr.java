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
package bazaar4idea.commands;

import bazaar4idea.BzrCommit;
import bazaar4idea.push.BzrPushSpec;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * @author Kirill Likhodedov
 */
public interface Bzr {

  @NotNull
  BzrCommandResult init(@NotNull Project project, @NotNull VirtualFile root, @NotNull BzrLineHandlerListener... listeners);

  @NotNull
  Set<VirtualFile> untrackedFiles(@NotNull Project project, @NotNull VirtualFile root,
                                  @Nullable Collection<VirtualFile> files) throws VcsException;

  // relativePaths are guaranteed to fit into command line length limitations.
  @NotNull
  Collection<VirtualFile> untrackedFilesNoChunk(@NotNull Project project, @NotNull VirtualFile root,
                                                @Nullable List<String> relativePaths) throws VcsException;

  @NotNull
  BzrCommandResult clone(@NotNull Project project, @NotNull File parentDirectory, @NotNull String url, @NotNull String clonedDirectoryName,
                         @NotNull BzrLineHandlerListener... progressListeners);

  @NotNull
  BzrCommandResult config(@NotNull BzrRepository repository, String... params);

  @NotNull
  BzrCommandResult diff(@NotNull BzrRepository repository, @NotNull List<String> parameters, @NotNull String range);

  @NotNull
  BzrCommandResult merge(@NotNull BzrRepository repository, @NotNull String branchToMerge, @Nullable List<String> additionalParams,
                         @NotNull BzrLineHandlerListener... listeners);

  @NotNull
  BzrCommandResult checkout(@NotNull BzrRepository repository, @NotNull String reference, @Nullable String newBranch, boolean force,
                            @NotNull BzrLineHandlerListener... listeners);

  @NotNull
  BzrCommandResult checkoutNewBranch(@NotNull BzrRepository repository, @NotNull String branchName,
                                     @Nullable BzrLineHandlerListener listener);

  @NotNull
  BzrCommandResult createNewTag(@NotNull BzrRepository repository, @NotNull String tagName,
                                @Nullable BzrLineHandlerListener listener, @NotNull String reference);

  @NotNull
  BzrCommandResult branchDelete(@NotNull BzrRepository repository, @NotNull String branchName, boolean force,
                                @NotNull BzrLineHandlerListener... listeners);

  @NotNull
  BzrCommandResult branchContains(@NotNull BzrRepository repository, @NotNull String commit);

  @NotNull
  BzrCommandResult branchCreate(@NotNull BzrRepository repository, @NotNull String branchName);

  @NotNull
  BzrCommandResult resetHard(@NotNull BzrRepository repository, @NotNull String revision);

  @NotNull
  BzrCommandResult resetMerge(@NotNull BzrRepository repository, @Nullable String revision);

  @NotNull
  BzrCommandResult tip(@NotNull BzrRepository repository, @NotNull String branchName);

  @NotNull
  BzrCommandResult push(@NotNull BzrRepository repository, @NotNull String remote, @NotNull String url, @NotNull String spec,
                        boolean updateTracking, @NotNull BzrLineHandlerListener... listeners);

  @NotNull
  BzrCommandResult push(@NotNull BzrRepository repository, @NotNull String remote, @NotNull String url, @NotNull String spec,
                        @NotNull BzrLineHandlerListener... listeners);

  @NotNull
  BzrCommandResult push(@NotNull BzrRepository repository, @NotNull BzrPushSpec spec, @NotNull String url,
                        @NotNull BzrLineHandlerListener... listeners);

  @NotNull
  BzrCommandResult show(@NotNull BzrRepository repository, @NotNull String... params);

  @NotNull
  BzrCommandResult getUnmergedFiles(@NotNull BzrRepository repository);

  @NotNull
  BzrCommandResult stashSave(@NotNull BzrRepository repository, @NotNull String message);

  @NotNull
  BzrCommandResult stashPop(@NotNull BzrRepository repository, @NotNull BzrLineHandlerListener... listeners);

  @NotNull
  List<BzrCommit> history(@NotNull BzrRepository repository, @NotNull String range);

  @NotNull
  BzrCommandResult fetch(@NotNull BzrRepository repository, @NotNull String url, @NotNull String remote, String... params);
}
