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
package bazaar4idea.ui.branch;

import bazaar4idea.BzrUtil;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * <p>
 *   Invokes a {@link BzrBranchPopup} to checkout and control Bazaar branches.
 * </p>
 *
 * @author Kirill Likhodedov
 */
public class BzrBranchesAction extends DumbAwareAction {

  @Override
  public void actionPerformed(AnActionEvent e) {
    final Project project = e.getData(CommonDataKeys.PROJECT);
    assert project != null;
    BzrRepositoryManager repositoryManager = BzrUtil.getRepositoryManager(project);
    VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
    BzrRepository repository = (file == null ?
                                BzrBranchUtil.getCurrentRepository(project):
                                repositoryManager.getRepositoryForRoot(BzrBranchUtil.getVcsRootOrGuess(project, file)));
    if (repository == null) {
      return;
    }

    BzrBranchPopup.getInstance(project, repository).asListPopup().showInBestPositionFor(e.getDataContext());
  }

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabled(project != null && !project.isDisposed());
  }
}
