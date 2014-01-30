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
package bazaar4idea.rebase;

import bazaar4idea.util.BzrUIUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

/**
 * The rebase action dialog
 */
public class BzrRebaseActionDialog extends DialogWrapper {
  /**
   * The root selector
   */
  private JComboBox myBzrRootComboBox;
  /**
   * The root panel
   */
  private JPanel myPanel;

  /**
   * A constructor
   *
   * @param project     the project to select
   * @param title       the dialog title
   * @param roots       the git repository roots for the project
   * @param defaultRoot the guessed default root
   */
  public BzrRebaseActionDialog(Project project, String title, List<VirtualFile> roots, VirtualFile defaultRoot) {
    super(project, true);
    BzrUIUtil.setupRootChooser(project, roots, defaultRoot, myBzrRootComboBox, null);
    setTitle(title);
    setOKButtonText(title);
    init();
  }


  /**
   * Show dialog and select root
   *
   * @return selected root or null if the dialog has been cancelled
   */
  @Nullable
  public VirtualFile selectRoot() {
    return isOK() ? (VirtualFile)myBzrRootComboBox.getSelectedItem() : null;
  }


  /**
   * {@inheritDoc}
   */
  protected JComponent createCenterPanel() {
    return myPanel;
  }
}
