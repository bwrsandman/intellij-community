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
package bazaar4idea.branch;

import bazaar4idea.BzrLocalBranch;
import bazaar4idea.BzrRemoteBranch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holder for Bazaar branch and the branch it is "connected" with. It is tracked branch or so called "matched" branch.
 *
 * @author Kirill Likhodedov
 */
public class BzrBranchPair {
  private @NotNull BzrLocalBranch myBranch;
  private @Nullable BzrRemoteBranch myDestBranch;

  public BzrBranchPair(@NotNull BzrLocalBranch branch, @Nullable BzrRemoteBranch destination) {
    myBranch = branch;
    myDestBranch = destination;
  }

  @NotNull
  public BzrLocalBranch getBranch() {
    return myBranch;
  }

  @Nullable
  public BzrRemoteBranch getDest() {
    return myDestBranch;
  }

}
