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
import bazaar4idea.config.BzrVcsSettings;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.repo.BzrRepositoryChangeListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

/**
 * Status bar widget which displays the current branch for the file currently open in the editor.
 * @author Kirill Likhodedov
 */
public class BzrBranchWidget extends EditorBasedWidget implements StatusBarWidget.MultipleTextValuesPresentation,
                                                                  StatusBarWidget.Multiframe, BzrRepositoryChangeListener {
  private final BzrVcsSettings mySettings;
  private volatile String myText = "";
  private volatile String myTooltip = "";
  private final String myMaxString;

  public BzrBranchWidget(Project project) {
    super(project);
    project.getMessageBus().connect().subscribe(BzrRepository.BAZAAR_REPO_CHANGE, this);
    mySettings = BzrVcsSettings.getInstance(project);
    myMaxString = "Bazaar: Rebasing master";
  }

  @Override
  public StatusBarWidget copy() {
    return new BzrBranchWidget(getProject());
  }

  @NotNull
  @Override
  public String ID() {
    return BzrBranchWidget.class.getName();
  }

  @Override
  public WidgetPresentation getPresentation(@NotNull PlatformType type) {
    return this;
  }

  @Override
  public void selectionChanged(@NotNull FileEditorManagerEvent event) {
    update();
  }

  @Override
  public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    update();
  }

  @Override
  public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    update();
  }

  @Override
  public void repositoryChanged(@NotNull BzrRepository repository) {
    update();
  }

  @Override
  public ListPopup getPopupStep() {
    Project project = getProject();
    if (project == null) {
      return null;
    }
    BzrRepository repo = BzrBranchUtil.getCurrentRepository(project);
    if (repo == null) {
      return null;
    }
    update(); // update on click
    return BzrBranchPopup.getInstance(project, repo).asListPopup();
  }

  @Override
  public String getSelectedValue() {
    final String text = myText;
    return StringUtil.isEmpty(text) ? "" : "Bazaar: " + text;
  }

  @NotNull
  @Override
  @Deprecated
  public String getMaxValue() {
    return myMaxString;
  }

  @Override
  public String getTooltipText() {
    return myTooltip;
  }

  @Override
  // have to effect since the click opens a list popup, and the consumer is not called for the MultipleTextValuesPresentation
  public Consumer<MouseEvent> getClickConsumer() {
    return new Consumer<MouseEvent>() {
      public void consume(MouseEvent mouseEvent) {
          update();
      }
    };
  }

  private void update() {
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        Project project = getProject();
        if (project == null) {
          emptyTextAndTooltip();
          return;
        }

        BzrRepository repo = BzrBranchUtil.getCurrentRepository(project);
        if (repo == null) { // the file is not under version control => display nothing
          emptyTextAndTooltip();
          return;
        }

        int maxLength = myMaxString.length() - 1; // -1, because there are arrows indicating that it is a popup
        myText = StringUtil.shortenTextWithEllipsis(BzrBranchUtil.getDisplayableBranchText(repo), maxLength, 5);
        myTooltip = getDisplayableBranchTooltip(repo);
        myStatusBar.updateWidget(ID());
        mySettings.setRecentRoot(repo.getRoot().getPath());
      }
    });
  }

  private void emptyTextAndTooltip() {
    myText = "";
    myTooltip = "";
  }

  @NotNull
  private static String getDisplayableBranchTooltip(BzrRepository repo) {
    String text = BzrBranchUtil.getDisplayableBranchText(repo);
    if (!BzrUtil.justOneBzrRepository(repo.getProject())) {
      return text + "\n" + "Root: " + repo.getRoot().getName();
    }
    return text;
  }

}
