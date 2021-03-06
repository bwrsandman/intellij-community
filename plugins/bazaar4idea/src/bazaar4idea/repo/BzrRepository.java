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
package bazaar4idea.repo;

import bazaar4idea.branch.BzrBranchesCollection;
import com.intellij.dvcs.repo.Repository;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.Topic;
import bazaar4idea.BzrLocalBranch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * <p>
 *   BzrRepository is a representation of a Bazaar repository stored under the specified directory.
 *   It stores the information about the repository, which is frequently requested by other plugin components.
 *   All get-methods (like {@link #getCurrentRevision()}) are just getters of the correspondent fields and thus are very fast.
 * </p>
 * <p>
 *   The BzrRepository is updated "externally" by the {@link BzrRepositoryUpdater}, when correspondent {@code .git/} service files
 *   change.
 * </p>
 * <p>
 *   To force asynchronous update, it is enough to call {@link VirtualFile#refresh(boolean, boolean) refresh} on the root directory.
 * </p>
 * <p>
 *   To make a synchronous update of the repository call {@link #update()}.
 *   Updating requires reading from disk, so it may take some time, however, updating the whole community repository took ~10 ms at the time
 *   of measurement, so must be fast enough. Better not to be called in AWT though.
 * </p>
 * <p>
 *   Other components may subscribe to BzrRepository changes via the {@link #BAZAAR_REPO_CHANGE} {@link Topic}
 * </p>
 *
 * <p>
 *   Getters and setters (update...()-methods) are not synchronized intentionally - to avoid live- and deadlocks.
 *   BzrRepository is updated asynchronously,
 *   so even if the getters would have been synchronized, it wouldn't guarantee that they return actual values (as they are in .git).
 *   <br/>
 *   If one needs a really 100 % up-to-date value, one should call {@link #update()} and then get...().
 *   update() is a synchronous read from .git, so it is guaranteed to query the real value.
 * </p>
 *
 * @author Kirill Likhodedov
 */
public interface BzrRepository extends Repository {

  Topic<BzrRepositoryChangeListener> BAZAAR_REPO_CHANGE = Topic.create("BzrRepository change", BzrRepositoryChangeListener.class);

  @NotNull
  VirtualFile getBzrDir();

  @NotNull
  BzrUntrackedFilesHolder getUntrackedFilesHolder();


  @NotNull
  BzrRepoInfo getInfo();

  /**
   * Returns the current branch of this Bazaar repository.
   * If the repository is being rebased, then the current branch is the branch being rebased (which was current before the rebase
   * operation has started).
   * Returns null, if the repository is not on a branch and not in the REBASING state.
   */
  @Nullable
  BzrLocalBranch getCurrentBranch();

  @NotNull
  BzrBranchesCollection getBranches();

  /**
   * Returns remotes defined in this Bazaar repository.
   * It is different from {@link BzrConfig#getRemotes()} because remotes may be defined not only in {@code .git/config},
   * but in {@code .git/remotes/} or even {@code .git/branches} as well.
   * On the other hand, it is a very old way to define remotes and we are not going to implement this until needed.
   * See <a href="http://thread.gmane.org/gmane.comp.version-control.git/182960">discussion in the Bazaar mailing list</a> that confirms
   * that remotes a defined in {@code .git/config} only nowadays.
   * @return BzrRemotes defined for this repository.
   */
  @NotNull
  Collection<BzrRemote> getRemotes();

  @NotNull
  Collection<BzrBranchTrackInfo> getBranchTrackInfos();

  boolean isRebaseInProgress();

  boolean isOnBranch();

}
