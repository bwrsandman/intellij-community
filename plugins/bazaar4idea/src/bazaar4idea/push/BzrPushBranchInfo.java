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

import bazaar4idea.BzrCommit;
import bazaar4idea.BzrLocalBranch;
import bazaar4idea.BzrRemoteBranch;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the list of commits and the destination branch (which a branch associated with this BzrPushBranchInfo is going to be pushed to).
 *
 * @author Kirill Likhodedov
 */
final class BzrPushBranchInfo {

  private final BzrLocalBranch mySourceBranch;
  private final BzrRemoteBranch myDestBranch;
  private final Type myType;
  private final List<BzrCommit> myCommits;

  enum Type {
    STANDARD,             // the branch this branch is targeted, exists (and is either the tracked/matched branch or manually specified)
    NEW_BRANCH,           // the source branch will be pushed to a new branch
    NO_TRACKED_OR_TARGET  // the branch has no tracked/matched, and target was not manually specified
  }

  BzrPushBranchInfo(@NotNull BzrLocalBranch sourceBranch,
                    @NotNull BzrRemoteBranch destBranch,
                    @NotNull List<BzrCommit> commits,
                    @NotNull Type type) {
    mySourceBranch = sourceBranch;
    myCommits = commits;
    myDestBranch = destBranch;
    myType = type;
  }

  BzrPushBranchInfo(@NotNull BzrPushBranchInfo pushBranchInfo) {
    this(pushBranchInfo.getSourceBranch(), pushBranchInfo.getDestBranch(), pushBranchInfo.getCommits(), pushBranchInfo.getType());
  }

  @NotNull
  Type getType() {
    return myType;
  }
  
  boolean isNewBranchCreated() {
    return myType == Type.NEW_BRANCH;
  }

  @NotNull
  BzrRemoteBranch getDestBranch() {
    return myDestBranch;
  }

  @NotNull
  List<BzrCommit> getCommits() {
    return new ArrayList<BzrCommit>(myCommits);
  }

  @NotNull
  public BzrLocalBranch getSourceBranch() {
    return mySourceBranch;
  }

  boolean isEmpty() {
    return myCommits.isEmpty();
  }

}
