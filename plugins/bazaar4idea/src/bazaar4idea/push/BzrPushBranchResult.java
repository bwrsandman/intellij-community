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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of pushing a single branch.
 * 
 * @author Kirill Likhodedov
 */
final class BzrPushBranchResult {

  private final Type myType;
  private final int myNumberOfPushedCommits;
  private final String myTargetBranchName;

  enum Type {
    SUCCESS,
    NEW_BRANCH,
    REJECTED,
    ERROR
  }

  private BzrPushBranchResult(Type type, int numberOfPushedCommits, @Nullable String targetBranchName) {
    myType = type;
    myNumberOfPushedCommits = numberOfPushedCommits;
    myTargetBranchName = targetBranchName;
  }
  
  static BzrPushBranchResult success(int numberOfPushedCommits) {
    return new BzrPushBranchResult(Type.SUCCESS, numberOfPushedCommits, null);
  }
  
  static BzrPushBranchResult newBranch(String targetBranchName) {
    return new BzrPushBranchResult(Type.NEW_BRANCH, 0, targetBranchName);
  }
  
  static BzrPushBranchResult rejected() {
    return new BzrPushBranchResult(Type.REJECTED, 0, null);
  }
  
  static BzrPushBranchResult error() {
    return new BzrPushBranchResult(Type.ERROR, 0, null);
  }
  
  int getNumberOfPushedCommits() {
    return myNumberOfPushedCommits;
  }
  
  boolean isSuccess() {
    return myType == Type.SUCCESS;
  }

  boolean isRejected() {
    return myType == Type.REJECTED;
  }
  
  boolean isError() {
    return myType == Type.ERROR;
  }

  boolean isNewBranch() {
    return myType == Type.NEW_BRANCH;
  }
  
  @NotNull
  String getTargetBranchName() {
    return myTargetBranchName != null ? myTargetBranchName : "";
  }

}
