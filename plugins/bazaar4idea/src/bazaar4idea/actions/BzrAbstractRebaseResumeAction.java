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

import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.rebase.BzrInteractiveRebaseEditorHandler;
import bazaar4idea.rebase.BzrRebaseActionDialog;
import bazaar4idea.rebase.BzrRebaseUtils;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NonNls;

import java.util.Iterator;
import java.util.List;

/**
 * Base class for git rebase [--skip, --continue] actions and git rebase operation
 */
public abstract class BzrAbstractRebaseResumeAction extends BzrRebaseActionBase {

  /**
   * {@inheritDoc}
   */
  protected BzrLineHandler createHandler(Project project, List<VirtualFile> gitRoots, VirtualFile defaultRoot) {
    for (Iterator<VirtualFile> i = gitRoots.iterator(); i.hasNext();) {
      if (!BzrRebaseUtils.isRebaseInTheProgress(i.next())) {
        i.remove();
      }
    }
    if (gitRoots.size() == 0) {
      Messages.showErrorDialog(project, BzrBundle.getString("rebase.action.no.root"), BzrBundle.getString("rebase.action.error"));
      return null;
    }
    final VirtualFile root;
    if (gitRoots.size() == 1) {
      root = gitRoots.get(0);
    }
    else {
      if (!gitRoots.contains(defaultRoot)) {
        defaultRoot = gitRoots.get(0);
      }
      BzrRebaseActionDialog d = new BzrRebaseActionDialog(project, getActionTitle(), gitRoots, defaultRoot);
      d.show();

      root = d.selectRoot();
      if (root == null) {
        return null;
      }
    }
    BzrLineHandler h = new BzrLineHandler(project, root, BzrCommand.REBASE);
    h.addParameters(getOptionName());
    return h;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void configureEditor(BzrInteractiveRebaseEditorHandler editor) {
    editor.setRebaseEditorShown();
  }

  /**
   * @return title for rebase operation
   */
  @NonNls
  protected abstract String getOptionName();

  /**
   * @return title for root selection dialog
   */
  protected abstract String getActionTitle();

  @Override
  protected boolean isEnabled(AnActionEvent e) {
    return super.isEnabled(e) && isRebasing(e);
  }
}
