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

import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.commands.BzrHandlerUtil;
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.merge.BzrMergeDialog;
import bazaar4idea.merge.BzrMergeUtil;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.history.Label;
import com.intellij.history.LocalHistory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.ActionInfo;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Bazaar "merge" action
 */
public class BzrMerge extends BzrRepositoryAction {

  /**
   * {@inheritDoc}
   */
  @Override
  @NotNull
  protected String getActionName() {
    return BzrBundle.getString("merge.action.name");
  }

  /**
   * {@inheritDoc}
   */
  protected void perform(@NotNull final Project project,
                         @NotNull final List<VirtualFile> gitRoots,
                         @NotNull final VirtualFile defaultRoot,
                         final Set<VirtualFile> affectedRoots,
                         final List<VcsException> exceptions) throws VcsException {
    BzrVcs vcs = BzrVcs.getInstance(project);
    if (vcs == null) {
      return;
    }
    BzrMergeDialog dialog = new BzrMergeDialog(project, gitRoots, defaultRoot);
    try {
      dialog.updateBranches();
    }
    catch (VcsException e) {
      if (vcs.getExecutableValidator().checkExecutableAndShowMessageIfNeeded(null)) {
        vcs.showErrors(Collections.singletonList(e), BzrBundle.getString("merge.retrieving.branches"));
      }
      return;
    }
    dialog.show();
    if (!dialog.isOK()) {
      return;
    }
    Label beforeLabel = LocalHistory.getInstance().putSystemLabel(project, "Before update");
    BzrLineHandler h = dialog.handler();
    final VirtualFile root = dialog.getSelectedRoot();
    affectedRoots.add(root);
    BzrRevisionNumber currentRev = BzrRevisionNumber.resolve(project, root, "HEAD");
    try {
      BzrHandlerUtil.doSynchronously(h, BzrBundle.message("merging.title", dialog.getSelectedRoot().getPath()), h.printableCommandLine());
    }
    finally {
      exceptions.addAll(h.errors());
      BzrRepositoryManager manager = BzrUtil.getRepositoryManager(project);
      manager.updateRepository(root);
    }
    if (exceptions.size() != 0) {
      return;
    }
    BzrMergeUtil.showUpdates(this, project, exceptions, root, currentRev, beforeLabel, getActionName(), ActionInfo.INTEGRATE);
  }
}
