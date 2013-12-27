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
package bazaar4idea.commands;

import bazaar4idea.BzrUtil;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Compound result of the Bazaar command execution performed on several repositories.
 * 
 * @author Kirill Likhodedov
 */
public final class BzrCompoundResult {
  
  private final Map<BzrRepository, BzrCommandResult> resultsByRepos = new HashMap<BzrRepository, BzrCommandResult>(1);
  private final Project myProject;

  public BzrCompoundResult(Project project) {
    myProject = project;
  }

  public void append(BzrRepository repository, BzrCommandResult result) {
    resultsByRepos.put(repository, result);
  }

  public boolean totalSuccess() {
    boolean success = true;
    for (BzrCommandResult result : resultsByRepos.values()) {
      success &= result.success();
    }
    return success;
  }

  /**
   * @return true if at least one, but not all repositories succeeded. 
   */
  public boolean partialSuccess() {
    boolean successFound = false;
    boolean failureFound = false;
    for (BzrCommandResult result : resultsByRepos.values()) {
      if (result.success()) {
        successFound = true;
      } else {
        failureFound = true;
      }
    }
    return successFound && failureFound;
  }

  /**
   * Constructs the HTML-formatted message from error outputs of failed repositories.
   * If there is only 1 repository in the project, just returns the error without writing the repository url (to avoid confusion for people
   * with only 1 root ever).
   * Otherwise adds repository URL to the error that repository produced.
   */
  @NotNull
  public String getErrorOutputWithReposIndication() {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<BzrRepository, BzrCommandResult> entry : resultsByRepos.entrySet()) {
      BzrRepository repository = entry.getKey();
      BzrCommandResult result = entry.getValue();
      if (!result.success()) {
        sb.append("<p>");
        if (!BzrUtil.justOneBzrRepository(myProject)) {
          sb.append("<code>" + repository.getPresentableUrl() + "</code>:<br/>");
        }
        sb.append(result.getErrorOutputAsHtmlString());
        sb.append("</p>");
      }
    }
    return sb.toString();
  }
}
