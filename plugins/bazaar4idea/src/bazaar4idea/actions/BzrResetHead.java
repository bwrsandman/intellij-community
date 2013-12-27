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

import bazaar4idea.BzrUtil;
import bazaar4idea.commands.BzrHandlerUtil;
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.ui.BzrResetDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * The reset action
 */
public class BzrResetHead extends BzrRepositoryAction {
  /**
   * {@inheritDoc}
   */
  @NotNull
  protected String getActionName() {
    return BzrBundle.getString("reset.action.name");
  }

  /**
   * {@inheritDoc}
   */
  protected void perform(@NotNull Project project,
                         @NotNull List<VirtualFile> gitRoots,
                         @NotNull VirtualFile defaultRoot,
                         Set<VirtualFile> affectedRoots,
                         List<VcsException> exceptions) throws VcsException {
    BzrResetDialog d = new BzrResetDialog(project, gitRoots, defaultRoot);
    d.show();
    if (!d.isOK()) {
      return;
    }
    BzrLineHandler h = d.handler();
    affectedRoots.add(d.getBzrRoot());
    BzrHandlerUtil.doSynchronously(h, BzrBundle.getString("resetting.title"), h.printableCommandLine());
    BzrRepositoryManager manager = BzrUtil.getRepositoryManager(project);
    manager.updateRepository(d.getBzrRoot());
  }
}
