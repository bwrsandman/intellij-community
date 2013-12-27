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
package bazaar4idea.history.wholeTree;

import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.config.BzrVersion;
import bazaar4idea.history.browser.BzrProjectLogManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.BackgroundFromStartOption;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.Function;
import com.intellij.util.SmartList;
import com.intellij.util.ui.AdjustComponentWhenShown;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * @author irengrig
 */
public class SelectRepositoryAndShowLogAction extends AnAction {
  public static final String ourTitle = "Show Bazaar repository log";
  public static final int MAX_REPOS = 10;

  public SelectRepositoryAndShowLogAction() {
    super(ourTitle + "...");
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = CommonDataKeys.PROJECT.getData(e.getDataContext());
    project = project == null ? ProjectManager.getInstance().getDefaultProject() : project;
    final Project finalProject = project;

    final FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, true, false, true);
    final VirtualFile[] virtualFiles = FileChooser.chooseFiles(descriptor, project, null);
    if (virtualFiles.length == 0) return;
    if (virtualFiles.length > MAX_REPOS) {
      VcsBalloonProblemNotifier.showOverVersionControlView(project, "Too many roots (more than " + MAX_REPOS +
        ") selected.", MessageType.ERROR);
      return;
    }
    final List<VirtualFile> wrongRoots = new SmartList<VirtualFile>();
    final List<VirtualFile> correctRoots = new SmartList<VirtualFile>();
    for (VirtualFile vf : virtualFiles) {
      if (! BzrUtil.isBzrRoot(new File(vf.getPath()))) {
        wrongRoots.add(vf);
      } else {
        correctRoots.add(vf);
      }
    }
    if (! wrongRoots.isEmpty()) {
      VcsBalloonProblemNotifier.showOverVersionControlView(project, "These files are not Bazaar repository roots:\n" +
        StringUtil.join(wrongRoots, new Function<VirtualFile, String>() {
                          @Override
                          public String fun(VirtualFile virtualFile) {
                            return virtualFile.getPath();
                          }
                        }, "\n"), MessageType.ERROR);
    }

    if (wrongRoots.size() != virtualFiles.length) {
      if (project == null || project.isDefault()) {
        ProgressManager.getInstance().run(new MyPrepareToShowForDefaultProject(null, correctRoots));
        return;
      }

      final ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ChangesViewContentManager.TOOLWINDOW_ID);

      final Runnable showContent = new Runnable() {
        @Override
        public void run() {
          ContentManager cm = window.getContentManager();
          if (checkForProjectScope(cm, finalProject, correctRoots)) return;

          int cnt = 0;
          Content[] contents = cm.getContents();
          for (Content content : contents) {
            final JComponent component = content.getComponent();
            if (component instanceof MyContentComponent) {
              cnt = Math.max(cnt, ((MyContentComponent)component).getCount());
              List<VirtualFile> roots = ((MyContentComponent)component).getRoots();
              if (Comparing.equal(roots, correctRoots)) {
                cm.setSelectedContent(content);
                alreadyOpened(finalProject);
                return;
              }
            }
          }

          LogFactoryService logFactoryService = LogFactoryService.getInstance(finalProject);
          final BzrLog bzrLog = logFactoryService.createComponent(false);
          bzrLog.rootsChanged(correctRoots);
          final ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
          ++cnt;
          MyContentComponent contentComponent = new MyContentComponent(new BorderLayout(), cnt);
          contentComponent.setRoots(correctRoots);
          contentComponent.add(bzrLog.getVisualComponent(), BorderLayout.CENTER);
          final Content content = contentFactory.createContent(contentComponent, "Log (" + cnt + ")", false);
          content.setDescription("Log for " + StringUtil.join(correctRoots, new Function<VirtualFile, String>() {
            @Override
            public String fun(VirtualFile file) {
              return file.getPath();
            }
          }, "\n"));
          content.setCloseable(true);
          Disposer.register(content, bzrLog);
          cm.addContent(content);
          cm.setSelectedContent(content);
        }
      };

      ProgressManager.getInstance().run(new MyCheckVersion(project) {
        @Override
        public void onSuccess() {
          if (myVersion == null) return;
          if (! window.isVisible()) {
            window.activate(showContent, true);
          } else {
            showContent.run();
          }
        }
      });
    }
  }

  private static void alreadyOpened(final Project project) {
    VcsBalloonProblemNotifier.showOverChangesView(project, "Already opened", MessageType.INFO);
  }

  private boolean checkForProjectScope(ContentManager cm, Project project, List<VirtualFile> correctRoots) {
    VirtualFile[] rootsUnderVcs = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(BzrVcs.getInstance(project));
    if (Comparing.equal(correctRoots, Arrays.asList(rootsUnderVcs))) {
      Content[] contents = cm.getContents();
      for (Content content : contents) {
        if (BzrProjectLogManager.CONTENT_KEY.equals(content.getDisplayName())) {
          cm.setSelectedContent(content);
          alreadyOpened(project);
          return true;
        }
      }
    }
    return false;
  }

  private static class MyContentComponent extends JPanel {
    private List<VirtualFile> myRoots;
    private int myCount;

    private MyContentComponent(LayoutManager layout, boolean isDoubleBuffered, int count) {
      super(layout, isDoubleBuffered);
      myCount = count;
    }

    private MyContentComponent(LayoutManager layout, int count) {
      super(layout);
      myCount = count;
    }

    private MyContentComponent(boolean isDoubleBuffered, int count) {
      super(isDoubleBuffered);
      myCount = count;
    }

    public int getCount() {
      return myCount;
    }

    private MyContentComponent(int count) {
      myCount = count;
    }

    public void setRoots(List<VirtualFile> roots) {
      myRoots = roots;
    }

    public List<VirtualFile> getRoots() {
      return myRoots;
    }
  }

  private static class MyPrepareToShowForDefaultProject extends Task.Backgroundable {
    private Project myProject;
    private final List<VirtualFile> myCorrectRoots;
    private MyCheckVersion myVersion;

    private MyPrepareToShowForDefaultProject(@Nullable Project project, List<VirtualFile> correctRoots) {
      super(project, ourTitle, true, BackgroundFromStartOption.getInstance());
      myCorrectRoots = correctRoots;
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
      myProject = ProjectManager.getInstance().getDefaultProject();
      myVersion = new MyCheckVersion(myProject);
      myVersion.run(indicator);
    }

    @Override
    public void onSuccess() {
      if (myVersion.myVersion.isNull()) return;
      if (myProject.isDisposed()) return;
      new MyDialog(myProject, myCorrectRoots).show();
    }
  }
  
  private static class MyCheckVersion extends Task.Backgroundable {
    protected BzrVersion myVersion;

    private MyCheckVersion(@Nullable Project project) {
      super(project, ourTitle, true, BackgroundFromStartOption.getInstance());
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
      BzrVcs vcs = BzrVcs.getInstance(myProject);
      if (vcs == null) { return; }
      myVersion = vcs.getVersion();
      if (myVersion.isNull()) {
        vcs.checkVersion();
        myVersion = vcs.getVersion();
      }
    }
  }

  private static class MyDialog extends DialogWrapper {
    private BzrLog myBzrLog;
    private final Project myProject;
    private final List<VirtualFile> myVirtualFiles;

    private MyDialog(Project project, final List<VirtualFile> virtualFiles) {
      super(project, true);
      myProject = project;
      myVirtualFiles = virtualFiles;
      myBzrLog = new LogFactoryService(myProject, ServiceManager.getService(BzrCommitsSequentially.class)).createComponent(false);
      myBzrLog.rootsChanged(myVirtualFiles);
      Disposer.register(getDisposable(), myBzrLog);
      new AdjustComponentWhenShown() {
        @Override
        protected boolean init() {
          myBzrLog.setModalityState(ModalityState.current());
          return true;
        }
      }.install(myBzrLog.getVisualComponent());
      setTitle("Bazaar Log");
      init();
    }

    @Override
    protected JComponent createCenterPanel() {
      return myBzrLog.getVisualComponent();
    }

    @NotNull
    @Override
    protected Action[] createActions() {
      return new Action[0];
    }
  }
}
