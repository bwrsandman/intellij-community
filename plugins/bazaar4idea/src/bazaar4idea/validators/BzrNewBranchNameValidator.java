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
package bazaar4idea.validators;

import bazaar4idea.BzrBranch;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.branch.BzrBranchesCollection;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.ui.InputValidatorEx;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * <p>
 *   In addition to {@link BzrRefNameValidator} checks that the entered branch name doesn't conflict
 *   with any existing local or remote branch.
 * </p>
 * <p>Use it when creating new branch.</p>
 * <p>
 *   If several repositories are specified (to create a branch in all of them at once, for example), all branches of all repositories are
 *   checked for conflicts.
 * </p>
 *
 * @author Kirill Likhodedov
 */
public final class BzrNewBranchNameValidator implements InputValidatorEx {

  private final Collection<BzrRepository> myRepositories;
  private String myErrorText;

  private BzrNewBranchNameValidator(@NotNull Collection<BzrRepository> repositories) {
    myRepositories = repositories;
  }

  public static BzrNewBranchNameValidator newInstance(@NotNull Collection<BzrRepository> repositories) {
    return new BzrNewBranchNameValidator(repositories);
  }

  @Override
  public boolean checkInput(String inputString) {
    if (!BzrRefNameValidator.getInstance().checkInput(inputString)){
      myErrorText = "Invalid name for branch";
      return false;
    }
    return checkBranchConflict(inputString);
  }

  private boolean checkBranchConflict(String inputString) {
    if (isNotPermitted(inputString) || conflictsWithLocalBranch(inputString) || conflictsWithRemoteBranch(inputString)) {
      return false;
    }
    myErrorText = null;
    return true;
  }

  private boolean isNotPermitted(@NotNull String inputString) {
    if (inputString.equalsIgnoreCase("head")) {
      myErrorText = "Branch name " + inputString + " is not valid";
      return true;
    }
    return false;
  }

  private boolean conflictsWithLocalBranch(String inputString) {
    return conflictsWithLocalOrRemote(inputString, true, " already exists");
  }

  private boolean conflictsWithRemoteBranch(String inputString) {
    return conflictsWithLocalOrRemote(inputString, false, " clashes with remote branch with the same name");
  }

  private boolean conflictsWithLocalOrRemote(String inputString, boolean local, String message) {
    for (BzrRepository repository : myRepositories) {
      BzrBranchesCollection branchesCollection = repository.getBranches();
      Collection<? extends BzrBranch> branches = local ? branchesCollection.getLocalBranches() : branchesCollection.getRemoteBranches();
      for (BzrBranch branch : branches) {
        if (branch.getName().equals(inputString)) {
          myErrorText = "Branch name " + inputString + message;
          if (myRepositories.size() > 1 && !allReposHaveBranch(inputString, local)) {
            myErrorText += " in repository " + repository.getPresentableUrl();
          }
          return true;
        }
      }
    }
    return false;
  }

  private boolean allReposHaveBranch(String inputString, boolean local) {
    for (BzrRepository repository : myRepositories) {
      BzrBranchesCollection branchesCollection = repository.getBranches();
      Collection<? extends BzrBranch> branches = local ? branchesCollection.getLocalBranches() : branchesCollection.getRemoteBranches();
      if (!BzrBranchUtil.convertBranchesToNames(branches).contains(inputString)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean canClose(String inputString) {
    return checkInput(inputString);
  }

  @Override
  public String getErrorText(String inputString) {
    return myErrorText;
  }
}
