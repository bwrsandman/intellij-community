/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package bazaar4idea.checkout;

import bazaar4idea.BzrUtil;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrLineHandlerPasswordRequestAware;
import bazaar4idea.commands.BzrTask;
import bazaar4idea.commands.BzrTaskResult;
import bazaar4idea.remote.BzrRememberedInputs;
import com.intellij.dvcs.DvcsRememberedInputs;
import com.intellij.dvcs.ui.CloneDvcsDialog;
import com.intellij.dvcs.ui.DvcsBundle;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * @author Nadya Zabrodina
 */
public class BzrCloneDialog extends CloneDvcsDialog {

  public BzrCloneDialog(@NotNull Project project) {
    super(project, BzrUtil.DOT_BZR);
  }

  /*
   * JGit doesn't have ls-remote command independent from repository yet.
   * That way, we have a hack here: if http response asked for a password, then the url is at least valid and existant, and we consider
   * that the test passed.
   */
  protected boolean test(@NotNull String url) {
    final BzrLineHandlerPasswordRequestAware handler =
      new BzrLineHandlerPasswordRequestAware(myProject, new File("."), BzrCommand.INFO);
    handler.setUrl(url);
    handler.addParameters(url);
    BzrTask task = new BzrTask(myProject, handler, DvcsBundle.message("clone.testing", url));
    BzrTaskResult result = task.executeModal();
    boolean authFailed = handler.hadAuthRequest();
    return result.isOK() || authFailed;
  }

  @NotNull
  @Override
  protected DvcsRememberedInputs getRememberedInputs() {
    return BzrRememberedInputs.getInstance();
  }

  @Override
  protected String getDimensionServiceKey() {
    return "BzrCloneDialog";
  }

  @Override
  protected String getHelpId() {
    return "reference.VersionControl.Bazaar.CloneRepository";
  }
}