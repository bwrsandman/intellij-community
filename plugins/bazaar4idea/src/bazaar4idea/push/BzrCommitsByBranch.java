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
package bazaar4idea.push;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrCommit;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Holds Bazaar commits made in a single repository grouped by branches.
 *
 * @author Kirill Likhodedov
 */
final class BzrCommitsByBranch {

  private final Map<BzrBranch, BzrPushBranchInfo> myCommitsByBranch;

  BzrCommitsByBranch(@NotNull Map<BzrBranch, BzrPushBranchInfo> commitsByBranch) {
    myCommitsByBranch = new HashMap<BzrBranch, BzrPushBranchInfo>(commitsByBranch);
  }

  BzrCommitsByBranch(BzrCommitsByBranch commitsByBranch) {
    this(new HashMap<BzrBranch, BzrPushBranchInfo>(commitsByBranch.myCommitsByBranch));
  }

  boolean isEmpty() {
    for (BzrPushBranchInfo info : myCommitsByBranch.values()) {
      if (!info.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  int commitsNumber() {
    int sum = 0;
    for (BzrPushBranchInfo branchInfo : myCommitsByBranch.values()) {
      sum += branchInfo.getCommits().size();
    }
    return sum;
  }

  @NotNull
  Collection<BzrBranch> getBranches() {
    return new HashSet<BzrBranch>(myCommitsByBranch.keySet());
  }

  @NotNull
  BzrPushBranchInfo get(@NotNull BzrBranch branch) {
    return new BzrPushBranchInfo(myCommitsByBranch.get(branch));
  }

  /**
   * Returns new BzrCommitsByBranch that contains commits only from the given branch (or nothing, if the given branch didn't exist in
   * the original structure).
   */
  @NotNull
  BzrCommitsByBranch retain(@NotNull BzrBranch branch) {
    Map<BzrBranch, BzrPushBranchInfo> res = new HashMap<BzrBranch, BzrPushBranchInfo>();
    if (myCommitsByBranch.containsKey(branch)) {
      res.put(branch, myCommitsByBranch.get(branch));
    }
    return new BzrCommitsByBranch(res);
  }

  @NotNull
  public Collection<BzrCommit> getAllCommits() {
    Collection<BzrCommit> commits = new ArrayList<BzrCommit>();
    for (BzrPushBranchInfo branchInfo : myCommitsByBranch.values()) {
      commits.addAll(branchInfo.getCommits());
    }
    return commits;
  }
}
