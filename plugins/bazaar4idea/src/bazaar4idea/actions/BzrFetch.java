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
import bazaar4idea.BzrVcs;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.update.BzrFetcher;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Bazaar "fetch" action
 */
public class BzrFetch extends BzrRepositoryAction {
  @Override
  @NotNull
  protected String getActionName() {
    return BzrBundle.getString("fetch.action.name");
  }

  protected void perform(@NotNull final Project project,
                         @NotNull final List<VirtualFile> gitRoots,
                         @NotNull final VirtualFile defaultRoot,
                         final Set<VirtualFile> affectedRoots,
                         final List<VcsException> exceptions) throws VcsException {
    BzrVcs.runInBackground(new Task.Backgroundable(project, "Fetching...", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        BzrRepositoryManager repositoryManager = BzrUtil.getRepositoryManager(project);
        new BzrFetcher(project, indicator, true)
          .fetchRootsAndNotify(BzrUtil.getRepositoriesFromRoots(repositoryManager, gitRoots), null, true);
      }
    });
  }

  @Override
  protected boolean executeFinalTasksSynchronously() {
    return false;
  }
}
