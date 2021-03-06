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
package bazaar4idea.history.wholeTree;

import bazaar4idea.branch.BzrBrancher;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;

import java.util.Collections;

/**
 * Created by IntelliJ IDEA.
 * User: Irina.Chernushina
 * Date: 10/26/11
 * Time: 3:07 PM
 */
public class BzrCreateNewTag {
  private final Project myProject;
  private final BzrRepository myRepository;
  private final String myReference;
  private final Runnable myCallInAwtAfterExecution;

  public BzrCreateNewTag(Project project, BzrRepository repository, String reference, Runnable callInAwtAfterExecution) {
    myProject = project;
    myRepository = repository;
    myReference = reference;
    myCallInAwtAfterExecution = callInAwtAfterExecution;
  }

  public void execute() {
    final String name = Messages.showInputDialog(myProject, "Enter the name of new tag", "Create New Tag On " + myReference,
                                                 Messages.getQuestionIcon(), "", new InputValidator() {
      @Override
      public boolean checkInput(String inputString) {
        return ! StringUtil.isEmpty(inputString) && ! StringUtil.containsWhitespaces(inputString);
      }

      @Override
      public boolean canClose(String inputString) {
        return ! StringUtil.isEmpty(inputString) && ! StringUtil.containsWhitespaces(inputString);
      }
    });
    if (name != null) {
      BzrBrancher brancher = ServiceManager.getService(myProject, BzrBrancher.class);
      brancher.createNewTag(name, myReference, Collections.singletonList(myRepository), myCallInAwtAfterExecution);
    }
  }
}
