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
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static bazaar4idea.util.BzrUIUtil.bold;
import static bazaar4idea.util.BzrUIUtil.code;

/**
 * If an error happens, all push is unsuccessful, for all branches.
 * Otherwise we've got separate results for branches.
 */
final class BzrPushRepoResult {

  enum Type {
    NOT_PUSHING,
    SUCCESS,
    SOME_REJECTED,
    ERROR,
    CANCEL,
    NOT_AUTHORIZED
  }

  private final Type myType;
  private final String myOutput;
  private final Map<BzrBranch, BzrPushBranchResult> myBranchResults;

  BzrPushRepoResult(@NotNull Type type, @NotNull Map<BzrBranch, BzrPushBranchResult> resultsByBranch, @NotNull String output) {
    myType = type;
    myBranchResults = resultsByBranch;
    myOutput = output;
  }

  @NotNull
  static BzrPushRepoResult success(@NotNull Map<BzrBranch, BzrPushBranchResult> resultsByBranch, @NotNull String output) {
    return new BzrPushRepoResult(Type.SUCCESS, resultsByBranch, output);
  }

  @NotNull
  static BzrPushRepoResult error(@NotNull Map<BzrBranch, BzrPushBranchResult> resultsByBranch, @NotNull String output) {
    return new BzrPushRepoResult(Type.ERROR, resultsByBranch, output);
  }

  @NotNull
  static BzrPushRepoResult someRejected(@NotNull Map<BzrBranch, BzrPushBranchResult> resultsByBranch, @NotNull String output) {
    return new BzrPushRepoResult(Type.SOME_REJECTED, resultsByBranch, output);
  }

  @NotNull
  public static BzrPushRepoResult cancelled(@NotNull String output) {
    return new BzrPushRepoResult(Type.CANCEL, Collections.<BzrBranch, BzrPushBranchResult>emptyMap(), output);
  }

  @NotNull
  public static BzrPushRepoResult notAuthorized(@NotNull String output) {
    return new BzrPushRepoResult(Type.NOT_AUTHORIZED, Collections.<BzrBranch, BzrPushBranchResult>emptyMap(), output);
  }

  @NotNull
  static BzrPushRepoResult notPushed() {
    return new BzrPushRepoResult(Type.NOT_PUSHING, Collections.<BzrBranch, BzrPushBranchResult>emptyMap(), "");
  }

  @NotNull
  Type getType() {
    return myType;
  }
  
  boolean isOneOfErrors() {
    return myType == Type.ERROR || myType == Type.CANCEL || myType == Type.NOT_AUTHORIZED;
  }

  @NotNull
  String getOutput() {
    return myOutput;
  }

  @NotNull
  Map<BzrBranch, BzrPushBranchResult> getBranchResults() {
    return myBranchResults;
  }

  @NotNull
  BzrPushRepoResult remove(@NotNull BzrBranch branch) {
    Map<BzrBranch, BzrPushBranchResult> resultsByBranch = new HashMap<BzrBranch, BzrPushBranchResult>();
    for (Map.Entry<BzrBranch, BzrPushBranchResult> entry : myBranchResults.entrySet()) {
      BzrBranch b = entry.getKey();
      if (!b.equals(branch)) {
        resultsByBranch.put(b, entry.getValue());
      }
    }
    return new BzrPushRepoResult(myType, resultsByBranch, myOutput);
  }

  boolean isEmpty() {
    return myBranchResults.isEmpty();
  }

  /**
   * Merges the given results to this result.
   * In the case of conflict (i.e. different results for a branch), current result is preferred over the previous one.
   */
  void mergeFrom(@NotNull BzrPushRepoResult repoResult) {
    for (Map.Entry<BzrBranch, BzrPushBranchResult> entry : repoResult.myBranchResults.entrySet()) {
      BzrBranch branch = entry.getKey();
      BzrPushBranchResult branchResult = entry.getValue();
      if (!myBranchResults.containsKey(branch)) {   // otherwise current result is preferred
        myBranchResults.put(branch, branchResult);
      }
    }
  }

  @NotNull
  String getPerBranchesNonErrorReport() {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (Map.Entry<BzrBranch, BzrPushBranchResult> entry : myBranchResults.entrySet()) {
      BzrBranch branch = entry.getKey();
      BzrPushBranchResult branchResult = entry.getValue();

      if (branchResult.isSuccess()) {
        sb.append(bold(branch.getName()) + ": pushed " + commits(branchResult.getNumberOfPushedCommits()));
      } 
      else if (branchResult.isNewBranch()) {
        sb.append(bold(branch.getName()) + " pushed to new branch " + bold(branchResult.getTargetBranchName()));
      } 
      else {
        sb.append(code(branch.getName())).append(": rejected");
      }
      
      if (i < myBranchResults.size() - 1) {
        sb.append("<br/>");
      }
    }
    return sb.toString();
  }

  @NotNull
  private static String commits(int commitNum) {
    return commitNum + " " + StringUtil.pluralize("commit", commitNum);
  }


}
