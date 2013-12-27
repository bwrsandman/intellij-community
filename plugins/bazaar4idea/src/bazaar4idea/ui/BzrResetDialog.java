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
 * The dialog for the "git reset" operation
 */
public class BzrResetDialog extends DialogWrapper {
  /**
   * The --soft reset type
   */
  private static final String SOFT = BzrBundle.getString("reset.type.soft");
  /**
   * The --mixed reset type
   */
  private static final String MIXED = BzrBundle.getString("reset.type.mixed");
  /**
   * The --hard reset type
   */
  private static final String HARD = BzrBundle.getString("reset.type.hard");
  /**
   * Bazaar root selector
   */
  private JComboBox myBzrRootComboBox;
  /**
   * The label for the current branch
   */
  private JLabel myCurrentBranchLabel;
  /**
   * The selector for reset type
   */
  private JComboBox myResetTypeComboBox;
  /**
   * The text field that contains commit expressions
   */
  private JTextField myCommitTextField;
  /**
   * The validate button
   */
  private JButton myValidateButton;
  /**
   * The root panel for the dialog
   */
  private JPanel myPanel;

  /**
   * The project
   */
  private final Project myProject;
  /**
   * The validator for commit text
   */
  private final BzrReferenceValidator myBzrReferenceValidator;

  /**
   * A constructor
   *
   * @param project     the project
   * @param roots       the list of the roots
   * @param defaultRoot the default root to select
   */
  public BzrResetDialog(final Project project, final List<VirtualFile> roots, final VirtualFile defaultRoot) {
    super(project, true);
    myProject = project;
    setTitle(BzrBundle.getString("reset.title"));
    setOKButtonText(BzrBundle.getString("reset.button"));
    myResetTypeComboBox.addItem(MIXED);
    myResetTypeComboBox.addItem(SOFT);
    myResetTypeComboBox.addItem(HARD);
    myResetTypeComboBox.setSelectedItem(MIXED);
    BzrUIUtil.setupRootChooser(project, roots, defaultRoot, myBzrRootComboBox, myCurrentBranchLabel);
    myBzrReferenceValidator = new BzrReferenceValidator(myProject, myBzrRootComboBox, myCommitTextField, myValidateButton, new Runnable() {
      public void run() {
        validateFields();
      }
    });
    init();
  }

  /**
   * Validate
   */
  void validateFields() {
    if (myBzrReferenceValidator.isInvalid()) {
      setErrorText(BzrBundle.getString("reset.commit.invalid"));
      setOKActionEnabled(false);
    }
    setErrorText(null);
    setOKActionEnabled(true);
  }

  /**
   * @return the handler for reset operation
   */
  public BzrLineHandler handler() {
    BzrLineHandler handler = new BzrLineHandler(myProject, getBzrRoot(), BzrCommand.RESET);
    String type = (String)myResetTypeComboBox.getSelectedItem();
    if (SOFT.equals(type)) {
      handler.addParameters("--soft");
    }
    else if (HARD.equals(type)) {
      handler.addParameters("--hard");
    }
    else if (MIXED.equals(type)) {
      handler.addParameters("--mixed");
    }
    final String commit = myCommitTextField.getText().trim();
    if (commit.length() != 0) {
      handler.addParameters(commit);
    }
    handler.endOptions();
    return handler;
  }

  /**
   * @return the selected git root
   */
  public VirtualFile getBzrRoot() {
    return (VirtualFile)myBzrRootComboBox.getSelectedItem();
  }

  /**
   * {@inheritDoc}
   */
  protected JComponent createCenterPanel() {
    return myPanel;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getDimensionServiceKey() {
    return getClass().getName();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getHelpId() {
    return "gitResetHead";
  }
}
