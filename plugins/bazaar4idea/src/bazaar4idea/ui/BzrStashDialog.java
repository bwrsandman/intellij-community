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
package bazaar4idea.ui;

import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrLineHandler;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.util.List;

/**
 * The git stash dialog.
 */
public class BzrStashDialog extends DialogWrapper {
  private JComboBox myBzrRootComboBox; // Bazaar root selector
  private JPanel myRootPanel;
  private JLabel myCurrentBranch;
  private JTextField myMessageTextField;
  private JCheckBox myKeepIndexCheckBox; // --keep-index
  private final Project myProject;

  /**
   * A constructor
   *
   * @param project     the project
   * @param roots       the list of Bazaar roots
   * @param defaultRoot the default root to select
   */
  public BzrStashDialog(final Project project, final List<VirtualFile> roots, final VirtualFile defaultRoot) {
    super(project, true);
    myProject = project;
    setTitle(BzrBundle.getString("stash.title"));
    setOKButtonText(BzrBundle.getString("stash.button"));
    BzrUIUtil.setupRootChooser(project, roots, defaultRoot, myBzrRootComboBox, myCurrentBranch);
    init();
  }

  public BzrLineHandler handler() {
    BzrLineHandler handler = new BzrLineHandler(myProject, getBzrRoot(), BzrCommand.STASH);
    handler.addParameters("save");
    if (myKeepIndexCheckBox.isSelected()) {
      handler.addParameters("--keep-index");
    }
    final String msg = myMessageTextField.getText().trim();
    if (msg.length() != 0) {
      handler.addParameters(msg);
    }
    return handler;
  }

  /**
   * @return the selected git root
   */
  public VirtualFile getBzrRoot() {
    return (VirtualFile)myBzrRootComboBox.getSelectedItem();
  }

  protected JComponent createCenterPanel() {
    return myRootPanel;
  }

  @Override
  protected String getDimensionServiceKey() {
    return getClass().getName();
  }

  @Override
  protected String getHelpId() {
    return "reference.VersionControl.Bazaar.Stash";
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myMessageTextField;
  }
}
