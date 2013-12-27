/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package bazaar4idea.actions;

import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.rebase.BzrRebaseDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Bazaar rebase action
 */
public class BzrRebase extends BzrRebaseActionBase {

  /**
   * {@inheritDoc}
   */
  @NotNull
  protected String getActionName() {
    return BzrBundle.getString("rebase.action.name");
  }

  /**
   * {@inheritDoc}
   */
  @Nullable
  protected BzrLineHandler createHandler(Project project, List<VirtualFile> gitRoots, VirtualFile defaultRoot) {
    BzrRebaseDialog dialog = new BzrRebaseDialog(project, gitRoots, defaultRoot);
    dialog.show();
    if (!dialog.isOK()) {
      return null;
    }
    return dialog.handler();
  }
}
