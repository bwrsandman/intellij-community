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
package bazaar4idea.ui.branch;

import bazaar4idea.BzrLocalBranch;
import bazaar4idea.BzrBranch;
import bazaar4idea.BzrRemoteBranch;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.repo.BzrBranchTrackInfo;
import bazaar4idea.repo.BzrRepository;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * @author Kirill Likhodedov
 */
public class BzrMultiRootBranchConfig {
  
  private final Collection<BzrRepository> myRepositories;

  public BzrMultiRootBranchConfig(@NotNull Collection<BzrRepository> repositories) {
    myRepositories = repositories;
  }

  boolean diverged() {
    return getCurrentBranch() == null;
  }
  
  @Nullable
  public String getCurrentBranch() {
    String commonBranch = null;
    for (BzrRepository repository : myRepositories) {
      BzrBranch branch = repository.getCurrentBranch();
      if (branch == null) {
        return null;
      }
      // NB: if all repositories are in the rebasing state on the same branches, this branch is returned
      if (commonBranch == null) {
        commonBranch = branch.getName();
      } else if (!commonBranch.equals(branch.getName())) {
        return null;
      }
    }
    return commonBranch;
  }
  
  @Nullable
  BzrRepository.State getState() {
    BzrRepository.State commonState = null;
    for (BzrRepository repository : myRepositories) {
      BzrRepository.State state = repository.getState();
      if (commonState == null) {
        commonState = state;
      } else if (!commonState.equals(state)) {
        return null;
      }
    }
    return commonState;
  }
  
  @NotNull
  Collection<String> getLocalBranches() {
    return BzrBranchUtil.getCommonBranches(myRepositories, true);
  }  

  @NotNull
  Collection<String> getRemoteBranches() {
    return BzrBranchUtil.getCommonBranches(myRepositories, false);
  }

  /**
   * If there is a common remote branch which is commonly tracked by the given branch in all repositories,
   * returns the name of this remote branch. Otherwise returns null. <br/>
   * For one repository just returns the tracked branch or null if there is no tracked branch.
   */
  @Nullable
  public String getTrackedBranch(@NotNull String branch) {
    return "TODO: getTrackedBranch " + branch;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (BzrRepository repository : myRepositories) {
      sb.append(repository.getPresentableUrl()).append(":").append(repository.getCurrentBranch()).append(":").append(repository.getState());
    }
    return sb.toString();
  }

}
