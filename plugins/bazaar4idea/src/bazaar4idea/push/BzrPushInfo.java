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
import bazaar4idea.repo.BzrRepository;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Holds information about a single Bazaar push action which is to be executed or has been executed.
 *
 * @author Kirill Likhodedov
 */
public final class BzrPushInfo {

  @NotNull private final BzrCommitsByRepoAndBranch myCommits;
  @NotNull private final Map<BzrRepository, BzrPushSpec> myPushSpecs;

  /**
   * We pass the complex {@link BzrCommitsByRepoAndBranch} structure here instead of just the list of repositories,
   * because later (after successful push, for example) it may be needed for showing useful notifications, such as number of commits pushed.
   */
  public BzrPushInfo(@NotNull BzrCommitsByRepoAndBranch commits, @NotNull Map<BzrRepository, BzrPushSpec> pushSpecs) {
    myCommits = commits;
    myPushSpecs = pushSpecs;
  }

  @NotNull
  public Map<BzrRepository, BzrPushSpec> getPushSpecs() {
    return myPushSpecs;
  }

  @NotNull
  public BzrCommitsByRepoAndBranch getCommits() {
    return myCommits;
  }

  @NotNull
  public BzrPushInfo retain(Map<BzrRepository, BzrBranch> repoBranchMap) {
    return new BzrPushInfo(myCommits.retainAll(repoBranchMap), new HashMap<BzrRepository, BzrPushSpec>(myPushSpecs));
  }
}
