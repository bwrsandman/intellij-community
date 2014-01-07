/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.vcsUtil.VcsFileUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Initialize Bazaar repository action
 */
public class BzrInit extends DumbAwareAction {

  @Override
  public void actionPerformed(final AnActionEvent e) {
    Project project = e.getData(CommonDataKeys.PROJECT);
    if (project == null) {
      project = ProjectManager.getInstance().getDefaultProject();
    }
    FileChooserDescriptor fcd = FileChooserDescriptorFactory.createSingleFolderDescriptor();
    fcd.setShowFileSystemRoots(true);
    fcd.setTitle(BzrBundle.getString("init.destination.directory.title"));
    fcd.setDescription(BzrBundle.getString("init.destination.directory.description"));
    fcd.setHideIgnored(false);
    VirtualFile baseDir = e.getData(CommonDataKeys.VIRTUAL_FILE);
    if (baseDir == null) {
      baseDir = project.getBaseDir();
    }
    doInit(project, fcd, baseDir, baseDir);
  }

  private static void doInit(final Project project, FileChooserDescriptor fcd, VirtualFile baseDir, final VirtualFile finalBaseDir) {
    FileChooser.chooseFile(fcd, project, baseDir, new Consumer<VirtualFile>() {
      @Override
      public void consume(final VirtualFile root) {
        if (BzrUtil.isUnderBzr(root) && Messages.showYesNoDialog(project,
                                                                 BzrBundle.message("init.warning.already.under.bzr",
                                                                                   StringUtil.escapeXml(root.getPresentableUrl())),
                                                                 BzrBundle.getString("init.warning.title"),
                                                                 Messages.getWarningIcon()) != Messages.YES) {
          return;
        }

        BzrCommandResult result = ServiceManager.getService(Bzr.class).init(project, root);
        if (!result.success()) {
          BzrVcs vcs = BzrVcs.getInstance(project);
          if (vcs != null && vcs.getExecutableValidator().checkExecutableAndNotifyIfNeeded()) {
            BzrUIUtil.notify(BzrVcs.IMPORTANT_ERROR_NOTIFICATION, project, "Bazaar init failed", result.getErrorOutputAsHtmlString(),
                             NotificationType.ERROR, null);
          }
          return;
        }

        if (project.isDefault()) {
          return;
        }
        final String path = root.equals(project.getBaseDir()) ? "" : root.getPath();
        BzrVcs.runInBackground(new Task.Backgroundable(project, BzrBundle.getString("common.refreshing")) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            refreshAndConfigureVcsMappings(project, root, path);
          }
        });
      }
    });
  }

  public static void refreshAndConfigureVcsMappings(final Project project, final VirtualFile root, final String path) {
    root.refresh(false, false);
    ProjectLevelVcsManager vcs = ProjectLevelVcsManager.getInstance(project);
    final List<VcsDirectoryMapping> vcsDirectoryMappings = new ArrayList<VcsDirectoryMapping>(vcs.getDirectoryMappings());
    VcsDirectoryMapping mapping = new VcsDirectoryMapping(path, BzrVcs.getInstance(project).getName());
    for (int i = 0; i < vcsDirectoryMappings.size(); i++) {
      final VcsDirectoryMapping m = vcsDirectoryMappings.get(i);
      if (m.getDirectory().equals(path)) {
        if (m.getVcs().length() == 0) {
          vcsDirectoryMappings.set(i, mapping);
          mapping = null;
          break;
        }
        else if (m.getVcs().equals(mapping.getVcs())) {
          mapping = null;
          break;
        }
      }
    }
    if (mapping != null) {
      vcsDirectoryMappings.add(mapping);
    }
    vcs.setDirectoryMappings(vcsDirectoryMappings);
    vcs.updateActiveVcss();
    VcsFileUtil.refreshFiles(project, Collections.singleton(root));
  }

}
