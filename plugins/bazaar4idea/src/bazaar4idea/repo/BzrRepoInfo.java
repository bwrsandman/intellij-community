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

import com.intellij.dvcs.repo.Repository;
import bazaar4idea.BzrBranch;
import bazaar4idea.BzrLocalBranch;
import bazaar4idea.BzrRemoteBranch;
import gnu.trove.THashSet;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author Kirill Likhodedov
 */
public class BzrRepoInfo {

  @Nullable private final BzrLocalBranch myCurrentBranch;
  @Nullable private final String myCurrentRevision;
  @NotNull private final Repository.State myState;
  @NotNull private final Set<BzrRemote> myRemotes;
  @NotNull private final Set<BzrLocalBranch> myLocalBranches;
  @NotNull private final Set<BzrRemoteBranch> myRemoteBranches;

  public BzrRepoInfo(@Nullable BzrLocalBranch currentBranch,
                     @Nullable String currentRevision,
                     @NotNull Repository.State state,
                     @NotNull Collection<BzrRemote> remotes,
                     @NotNull Collection<BzrLocalBranch> localBranches,
                     @NotNull Collection<BzrRemoteBranch> remoteBranches) {
    myCurrentBranch = currentBranch;
    myCurrentRevision = currentRevision;
    myState = state;
    myRemotes = new LinkedHashSet<BzrRemote>(remotes);
    myLocalBranches = new LinkedHashSet<BzrLocalBranch>(localBranches);
    myRemoteBranches = new LinkedHashSet<BzrRemoteBranch>(remoteBranches);
  }

  @Nullable
  public BzrLocalBranch getCurrentBranch() {
    return myCurrentBranch;
  }

  @NotNull
  public Collection<BzrRemote> getRemotes() {
    return myRemotes;
  }

  @NotNull
  public Collection<BzrLocalBranch> getLocalBranches() {
    return myLocalBranches;
  }

  @NotNull
  public Collection<BzrRemoteBranch> getRemoteBranches() {
    return myRemoteBranches;
  }

  @Nullable
  public String getCurrentRevision() {
    return myCurrentRevision;
  }

  @NotNull
  public Repository.State getState() {
    return myState;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    BzrRepoInfo info = (BzrRepoInfo)o;

    if (myState != info.myState) return false;
    if (myCurrentRevision != null ? !myCurrentRevision.equals(info.myCurrentRevision) : info.myCurrentRevision != null) return false;
    if (myCurrentBranch != null ? !myCurrentBranch.equals(info.myCurrentBranch) : info.myCurrentBranch != null) return false;
    if (!myRemotes.equals(info.myRemotes)) return false;
    if (!areEqual(myLocalBranches, info.myLocalBranches)) return false;
    if (!areEqual(myRemoteBranches, info.myRemoteBranches)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = myCurrentBranch != null ? myCurrentBranch.hashCode() : 0;
    result = 31 * result + (myCurrentRevision != null ? myCurrentRevision.hashCode() : 0);
    result = 31 * result + myState.hashCode();
    result = 31 * result + myRemotes.hashCode();
    result = 31 * result + myLocalBranches.hashCode();
    result = 31 * result + myRemoteBranches.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return String.format("BzrRepoInfo{current=%s, remotes=%s, localBranches=%s, remoteBranches=%s}",
                         myCurrentBranch, myRemotes, myLocalBranches, myRemoteBranches);
  }

  private static <T extends BzrBranch> boolean areEqual(Collection<T> c1, Collection<T> c2) {
    THashSet<BzrBranch> set1 = new THashSet<BzrBranch>(c1, new BranchesComparingStrategy());
    THashSet<BzrBranch> set2 = new THashSet<BzrBranch>(c2, new BranchesComparingStrategy());
    return set1.equals(set2);
  }

  private static class BranchesComparingStrategy implements TObjectHashingStrategy<BzrBranch> {

    @Override
    public int computeHashCode(@NotNull BzrBranch branch) {
      return 31 * branch.getName().hashCode() + branch.getHash().hashCode();
    }

    @Override
    public boolean equals(@NotNull BzrBranch b1, @NotNull BzrBranch b2) {
      if (b1 == b2) {
        return true;
      }
      if (b1.getClass() != b2.getClass()) {
        return false;
      }
      return b1.getName().equals(b2.getName()) && b1.getHash().equals(b2.getHash());
    }
  }

}
