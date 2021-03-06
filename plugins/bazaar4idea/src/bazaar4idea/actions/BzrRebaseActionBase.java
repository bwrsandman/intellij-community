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
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.commands.BzrTask;
import bazaar4idea.commands.BzrTaskResult;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.rebase.BzrInteractiveRebaseEditorHandler;
import bazaar4idea.rebase.BzrRebaseEditorService;
import bazaar4idea.rebase.BzrRebaseLineListener;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.commands.BzrTaskResultHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * The base class for rebase actions that use editor
 */
public abstract class BzrRebaseActionBase extends BzrRepositoryAction {
  /**
   * {@inheritDoc}
   */
  protected void perform(@NotNull final Project project,
                         @NotNull final List<VirtualFile> gitRoots,
                         @NotNull final VirtualFile defaultRoot,
                         final Set<VirtualFile> affectedRoots,
                         final List<VcsException> exceptions) throws VcsException {
    BzrLineHandler h = createHandler(project, gitRoots, defaultRoot);
    if (h == null) {
      return;
    }
    final VirtualFile root = h.workingDirectoryFile();
    BzrRebaseEditorService service = BzrRebaseEditorService.getInstance();
    final BzrInteractiveRebaseEditorHandler editor = new BzrInteractiveRebaseEditorHandler(service, project, root, h);
    final BzrRebaseLineListener resultListener = new BzrRebaseLineListener();
    h.addLineListener(resultListener);
    configureEditor(editor);
    affectedRoots.add(root);

    service.configureHandler(h, editor.getHandlerNo());
    BzrTask task = new BzrTask(project, h, BzrBundle.getString("rebasing.title"));
    task.executeInBackground(false, new BzrTaskResultHandlerAdapter() {
      @Override
      protected void run(BzrTaskResult taskResult) {
        editor.close();
        BzrRepositoryManager manager = BzrUtil.getRepositoryManager(project);
        manager.updateRepository(root);
        root.refresh(false, true);
        notifyAboutErrorResult(taskResult, resultListener, exceptions, project);
      }
    });
  }

  private static void notifyAboutErrorResult(BzrTaskResult taskResult, BzrRebaseLineListener resultListener, List<VcsException> exceptions, Project project) {
    if (taskResult == BzrTaskResult.CANCELLED) {
      return;
    }
    final BzrRebaseLineListener.Result result = resultListener.getResult();
    String messageId;
    boolean isError = true;
    switch (result.status) {
      case CONFLICT:
        messageId = "rebase.result.conflict";
        break;
      case ERROR:
        messageId = "rebase.result.error";
        break;
      case CANCELLED:
        // we do not need to show a message if editing was cancelled.
        exceptions.clear();
        return;
      case EDIT:
        isError = false;
        messageId = "rebase.result.amend";
        break;
      case FINISHED:
      default:
        messageId = null;
    }
    if (messageId != null) {
      String message = BzrBundle.message(messageId, result.current, result.total);
      String title = BzrBundle.message(messageId + ".title");
      if (isError) {
        Messages.showErrorDialog(project, message, title);
      }
      else {
        Messages.showInfoMessage(project, message, title);
      }
    }
  }

  /**
   * This method could be overridden to supply additional information to the editor.
   *
   * @param editor the editor to configure
   */
  protected void configureEditor(BzrInteractiveRebaseEditorHandler editor) {
  }

  /**
   * Create line handler that represents a git operation
   *
   * @param project     the context project
   * @param gitRoots    the git roots
   * @param defaultRoot the default root
   * @return the line handler or null
   */
  @Nullable
  protected abstract BzrLineHandler createHandler(Project project, List<VirtualFile> gitRoots, VirtualFile defaultRoot);
}
