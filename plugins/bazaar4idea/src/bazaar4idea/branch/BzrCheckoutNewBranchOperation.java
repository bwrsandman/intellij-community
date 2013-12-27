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
package bazaar4idea.branch;

import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.commands.BzrCompoundResult;
import bazaar4idea.commands.BzrSimpleEventDetector;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import bazaar4idea.BzrPlatformFacade;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

import static bazaar4idea.util.BzrUIUtil.code;

/**
 * Create new branch (starting from the current branch) and check it out.
 *
 * @author Kirill Likhodedov
 */
class BzrCheckoutNewBranchOperation extends BzrBranchOperation {

  @NotNull private final Project myProject;
  @NotNull private final String myNewBranchName;

  BzrCheckoutNewBranchOperation(@NotNull Project project,
                                BzrPlatformFacade facade,
                                @NotNull Bzr bzr,
                                @NotNull BzrBranchUiHandler uiHandler,
                                @NotNull Collection<BzrRepository> repositories,
                                @NotNull String newBranchName) {
    super(project, facade, bzr, uiHandler, repositories);
    myNewBranchName = newBranchName;
    myProject = project;
  }

  @Override
  protected void execute() {
    boolean fatalErrorHappened = false;
    while (hasMoreRepositories() && !fatalErrorHappened) {
      final BzrRepository repository = next();

      BzrSimpleEventDetector unmergedDetector = new BzrSimpleEventDetector(BzrSimpleEventDetector.Event.UNMERGED_PREVENTING_CHECKOUT);
      BzrCommandResult result = myBzr.checkoutNewBranch(repository, myNewBranchName, unmergedDetector);

      if (result.success()) {
        refresh(repository);
        markSuccessful(repository);
      }
      else if (unmergedDetector.hasHappened()) {
        fatalUnmergedFilesError();
        fatalErrorHappened = true;
      }
      else {
        fatalError("Couldn't create new branch " + myNewBranchName, result.getErrorOutputAsJoinedString());
        fatalErrorHappened = true;
      }
    }

    if (!fatalErrorHappened) {
      notifySuccess();
      updateRecentBranch();
    }
  }

  private static void refresh(@NotNull BzrRepository repository) {
    repository.update();
  }

  @NotNull
  @Override
  public String getSuccessMessage() {
    return String.format("Branch <b><code>%s</code></b> was created", myNewBranchName);
  }

  @NotNull
  @Override
  protected String getRollbackProposal() {
    return "However checkout has succeeded for the following " + repositories() + ":<br/>" +
           successfulRepositoriesJoined() +
           "<br/>You may rollback (checkout back to " + myCurrentBranchOrRev + " and delete " + myNewBranchName + ") not to let branches diverge.";
  }

  @NotNull
  @Override
  protected String getOperationName() {
    return "checkout";
  }

  @Override
  protected void rollback() {
    BzrCompoundResult checkoutResult = new BzrCompoundResult(myProject);
    BzrCompoundResult deleteResult = new BzrCompoundResult(myProject);
    Collection<BzrRepository> repositories = getSuccessfulRepositories();
    for (BzrRepository repository : repositories) {
      BzrCommandResult result = myBzr.checkout(repository, myCurrentBranchOrRev, null, true);
      checkoutResult.append(repository, result);
      if (result.success()) {
        deleteResult.append(repository, myBzr.branchDelete(repository, myNewBranchName, false));
      }
      refresh(repository);
    }
    if (checkoutResult.totalSuccess() && deleteResult.totalSuccess()) {
      myUiHandler.notifySuccess("Rollback successful", String.format("Checked out %s and deleted %s on %s %s", code(myCurrentBranchOrRev), code(myNewBranchName),
                                           StringUtil.pluralize("root", repositories.size()), successfulRepositoriesJoined()));
    }
    else {
      StringBuilder message = new StringBuilder();
      if (!checkoutResult.totalSuccess()) {
        message.append("Errors during checkout: ");
        message.append(checkoutResult.getErrorOutputWithReposIndication());
      }
      if (!deleteResult.totalSuccess()) {
        message.append("Errors during deleting ").append(code(myNewBranchName));
        message.append(deleteResult.getErrorOutputWithReposIndication());
      }
      myUiHandler.notifyError("Error during rollback", message.toString());
    }
  }

}
