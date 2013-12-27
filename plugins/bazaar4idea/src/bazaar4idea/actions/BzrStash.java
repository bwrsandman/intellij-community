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

import bazaar4idea.commands.BzrHandlerUtil;
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.ui.BzrStashDialog;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.BzrPlatformFacade;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Bazaar stash action
 */
public class BzrStash extends BzrRepositoryAction {

  /**
   * {@inheritDoc}
   */
  protected void perform(@NotNull final Project project,
                         @NotNull final List<VirtualFile> gitRoots,
                         @NotNull final VirtualFile defaultRoot,
                         final Set<VirtualFile> affectedRoots,
                         final List<VcsException> exceptions) throws VcsException {
    final ChangeListManager changeListManager = ChangeListManager.getInstance(project);
    if (changeListManager.isFreezedWithNotification("Can not stash changes now")) return;
    BzrStashDialog d = new BzrStashDialog(project, gitRoots, defaultRoot);
    d.show();
    if (!d.isOK()) {
      return;
    }
    VirtualFile root = d.getBzrRoot();
    affectedRoots.add(root);
    final BzrLineHandler h = d.handler();
    BzrHandlerUtil.doSynchronously(h, BzrBundle.getString("stashing.title"), h.printableCommandLine());
    ServiceManager.getService(project, BzrPlatformFacade.class).hardRefresh(root);
  }

  /**
   * {@inheritDoc}
   */
  @NotNull
  protected String getActionName() {
    return BzrBundle.getString("stash.action.name");
  }
}
