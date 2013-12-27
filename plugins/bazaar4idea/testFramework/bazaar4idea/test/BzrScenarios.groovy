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
package bazaar4idea.test

import bazaar4idea.repo.BzrRepository
import static BzrExecutor.*;
/**
 * Create popular scenarios used in multiple tests, for example:
 *  - create a branch and commit something there;
 *  - make some unmerged files in the working tree;
 *  - make the situation when local changes would be overwritten by merge.
 *
 * @author Kirill Likhodedov
 */
class BzrScenarios {

  private static final String BRANCH_FOR_UNMERGED_CONFLICTS = "unmerged_files_branch_" + Math.random();

  static final def LOCAL_CHANGES_OVERWRITTEN_BY = [
          initial:    "common content\ncommon content\ncommon content\n",
          branchLine: "line with branch changes\n",
          masterLine: "line with master changes"
  ]

  /**
   * Create a branch with a commit and return back to master.
   */
  static def branchWithCommit(BzrRepository repository, String name, String file = "branch_file.txt", String content = "branch content") {
    cd repository
    bzr("checkout -b $name")
    touch(file, content)
    bzr("add $file")
    bzr("commit -m branch_content")

    bzr("checkout master")
  }

  /**
   * Create a branch with a commit and return back to master.
   */
  static def branchWithCommit(Collection<BzrRepository> repositories, String name,
                       String file = "branch_file.txt", String content = "branch content") {
    repositories.each { branchWithCommit(it, name, file, content) }
  }

  /**
   * Make an unmerged file in the repository.
   */
  static def unmergedFiles(BzrRepository repository) {
    conflict(repository, BRANCH_FOR_UNMERGED_CONFLICTS, "unmerged.txt")
    bzr("merge $BRANCH_FOR_UNMERGED_CONFLICTS")
    bzr("branch -D $BRANCH_FOR_UNMERGED_CONFLICTS")
  }

  /**
   * Creates a branch with the given name, and produces conflicted content in a file between this branch and master.
   * Branch must not exist at this point.
   */
  static def conflict(BzrRepository repository, String branch, String file="conflict.txt") {
    assert "Branch [$branch] shouldn't exist for this scenario" : !branchExists(repository, branch)

    cd repository

    touch(file, "initial content")
    bzr("add $file")
    bzr("commit -m initial_content")

    bzr("checkout -b $branch")
    echo(file, "branch content")
    bzr("commit -am branch_content")

    bzr("checkout master")
    echo(file, "master content")
    bzr("commit -am master_content")
  }

  /**
   * Create an untracked file in master and a tracked file with the same name in the branch.
   * This produces the "some untracked files would be overwritten by..." error when trying to checkout or merge.
   * Branch with the given name shall exist.
   */
  static def untrackedFileOverwrittenBy(BzrRepository repository, String branch, Collection<String> fileNames) {
    cd repository
    bzr("checkout $branch")

    for (it in fileNames) {
      touch(it, "branch content")
      bzr("add $it")
    }

    bzr("commit -m untracked_files")
    bzr("checkout master")

    for (it in fileNames) {
      touch(it, "master content")
    }
  }

  /**
   * Creates a file in both master and branch so that the content differs, but can be merged without conflicts.
   * That way, bzr checkout/merge will fail with "local changes would be overwritten by checkout/merge",
   * but smart checkout/merge (stash-checkout/merge-unstash) would succeed without conflicts.
   *
   * NB: the branch should not exist before this is called!
   */
  static def localChangesOverwrittenByWithoutConflict(BzrRepository repository, String branch, Collection<String> fileNames) {
    cd repository

    for (it in fileNames) {
      echo(it, LOCAL_CHANGES_OVERWRITTEN_BY.initial)
      bzr("add $it")
    }
    bzr("commit -m initial_changes")

    bzr("checkout -b $branch")
    for (it in fileNames) {
      prepend(it, LOCAL_CHANGES_OVERWRITTEN_BY.branchLine)
      bzr("add $it")
    }
    bzr("commit -m branch_changes")

    bzr("checkout master")
    for (it in fileNames) {
      append(it, LOCAL_CHANGES_OVERWRITTEN_BY.masterLine)
    }
  }

  static def append(String fileName, String content) {
     echo(fileName, content)
  }

  static def prepend(String fileName, String content) {
    def previousContent = cat(fileName)
    new File(pwd(), fileName).withWriter("UTF-8") { it.write(content + previousContent) }
  }

  static def commit(BzrRepository repository, String file = "just_a_file_${Math.random()}.txt") {
    cd repository
    touch(file)
    bzr("add $file")
    bzr("commit -m just_a_commit")
  }

  public static boolean branchExists(BzrRepository repo = null, String branch) {
    bzr(repo, "branch").contains(branch)
  }

  public static void checkoutOrCreate(BzrRepository repository = null, String branch) {
    if (branch.equals("master") || branchExists(repository, branch)) {
      bzr("checkout $branch")
    }
    else {
      bzr("checkout -b $branch")
    }
  }

  public static void checkout(String branch) {
    bzr("checkout " + branch);
  }

}
