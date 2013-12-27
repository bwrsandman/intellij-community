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
import bazaar4idea.repo.BzrRepository;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Holds Bazaar commits grouped by repositories and by branches.
 * Actually, it is just a map (of maps of lists of commits) encapsulated in a separated class with some handy methods.
 *
 * @author Kirill Likhodedov
 */
final class BzrCommitsByRepoAndBranch {

  private final Map<BzrRepository, BzrCommitsByBranch> myCommitsByRepository;

  BzrCommitsByRepoAndBranch(@NotNull Map<BzrRepository, BzrCommitsByBranch> commitsByRepository) {
    myCommitsByRepository = commitsByRepository;
  }

  @NotNull
  static BzrCommitsByRepoAndBranch empty() {
    return new BzrCommitsByRepoAndBranch(new HashMap<BzrRepository, BzrCommitsByBranch>());
  }

  @NotNull
  Collection<BzrRepository> getRepositories() {
    return new HashSet<BzrRepository>(myCommitsByRepository.keySet());
  }

  @NotNull
  BzrCommitsByBranch get(@NotNull BzrRepository repository) {
    return new BzrCommitsByBranch(myCommitsByRepository.get(repository));
  }

  /**
   * Creates new BzrCommitByRepoAndBranch structure with only those repositories, which exist in the specified collection.
   */
  @NotNull
  BzrCommitsByRepoAndBranch retainAll(@NotNull Collection<BzrRepository> repositories) {
    Map<BzrRepository, BzrCommitsByBranch> commits = new HashMap<BzrRepository, BzrCommitsByBranch>();
    for (BzrRepository selectedRepository : repositories) {
      BzrCommitsByBranch value = myCommitsByRepository.get(selectedRepository);
      if (value != null) {
        commits.put(selectedRepository, value);
      }
    }
    return new BzrCommitsByRepoAndBranch(commits);
  }

  /**
   * Creates new BzrCommitByRepoAndBranch structure with only those pairs repository-branch, which exist in the specified map.
   */
  @NotNull
  BzrCommitsByRepoAndBranch retainAll(@NotNull Map<BzrRepository, BzrBranch> repositoriesBranches) {
    Map<BzrRepository, BzrCommitsByBranch> commits = new HashMap<BzrRepository, BzrCommitsByBranch>();
    for (BzrRepository repository : repositoriesBranches.keySet()) {
      BzrCommitsByBranch commitsByBranch = myCommitsByRepository.get(repository);
      if (commitsByBranch != null) {
        commits.put(repository, commitsByBranch.retain(repositoriesBranches.get(repository)));
      }
    }
    return new BzrCommitsByRepoAndBranch(commits);
  }

  @NotNull
  public Collection<BzrCommit> getAllCommits() {
    Collection<BzrCommit> commits = new ArrayList<BzrCommit>();
    for (BzrCommitsByBranch commitsByBranch : myCommitsByRepository.values()) {
      commits.addAll(commitsByBranch.getAllCommits());
    }
    return commits;
  }

}

