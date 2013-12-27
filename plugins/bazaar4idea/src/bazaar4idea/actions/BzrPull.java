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
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.commands.BzrStandardProgressAnalyzer;
import bazaar4idea.commands.BzrTask;
import bazaar4idea.commands.BzrTaskResultHandlerAdapter;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.merge.BzrMergeUtil;
import bazaar4idea.merge.BzrPullDialog;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.history.Label;
import com.intellij.history.LocalHistory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.ActionInfo;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Bazaar "pull" action
 */
public class BzrPull extends BzrRepositoryAction {

  @Override
  @NotNull
  protected String getActionName() {
    return BzrBundle.getString("pull.action.name");
  }

  protected void perform(@NotNull final Project project,
                         @NotNull final List<VirtualFile> gitRoots,
                         @NotNull final VirtualFile defaultRoot,
                         final Set<VirtualFile> affectedRoots,
                         final List<VcsException> exceptions) throws VcsException {
    final BzrPullDialog dialog = new BzrPullDialog(project, gitRoots, defaultRoot);
    dialog.show();
    if (!dialog.isOK()) {
      return;
    }
    final Label beforeLabel = LocalHistory.getInstance().putSystemLabel(project, "Before update");
    
    new Task.Backgroundable(project, BzrBundle.message("pulling.title", dialog.getRemote()), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        final BzrRepositoryManager repositoryManager = BzrUtil.getRepositoryManager(myProject);

        BzrRepository repository = repositoryManager.getRepositoryForRoot(dialog.gitRoot());
        assert repository != null : "Repository can't be null for root " + dialog.gitRoot();
        String remoteOrUrl = dialog.getRemote();
        
        
        BzrRemote remote = BzrUtil.findRemoteByName(repository, remoteOrUrl);
        String url = (remote == null) ? remoteOrUrl : remote.getFirstUrl();
        if (url == null) {
          return;
        }

        final BzrLineHandler handler = dialog.makeHandler(url);

        final VirtualFile root = dialog.gitRoot();
        affectedRoots.add(root);
        String revision = repository.getCurrentRevision();
        if (revision == null) {
          return;
        }
        final BzrRevisionNumber currentRev = new BzrRevisionNumber(revision);
    
        BzrTask pullTask = new BzrTask(project, handler, BzrBundle.message("pulling.title", dialog.getRemote()));
        pullTask.setProgressIndicator(indicator);
        pullTask.setProgressAnalyzer(new BzrStandardProgressAnalyzer());
        pullTask.execute(true, false, new BzrTaskResultHandlerAdapter() {
          @Override
          protected void onSuccess() {
            root.refresh(false, true);
            BzrMergeUtil.showUpdates(BzrPull.this, project, exceptions, root, currentRev, beforeLabel, getActionName(), ActionInfo.UPDATE);
            repositoryManager.updateRepository(root);
            runFinalTasks(project, BzrVcs.getInstance(project), affectedRoots, getActionName(), exceptions);
          }
    
          @Override
          protected void onFailure() {
            BzrUIUtil.notifyBzrErrors(project, "Error pulling " + dialog.getRemote(), "", handler.errors());
            repositoryManager.updateRepository(root);
          }
        });
      }
    }.queue();
  }

  @Override
  protected boolean executeFinalTasksSynchronously() {
    return false;
  }
}
