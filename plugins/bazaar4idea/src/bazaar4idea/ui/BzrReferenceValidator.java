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

import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The class setups validation for references in the text fields.
 */
public class BzrReferenceValidator {
  /**
   * The result of last validation
   */
  private boolean myLastResult;
  /**
   * The text that was used for last validation
   */
  private String myLastResultText = null;
  /**
   * The project
   */
  private final Project myProject;
  /**
   * The git root combobox
   */
  private final JComboBox myBzrRoot;
  /**
   * The text field that contains object reference
   */
  private final JTextField myTextField;
  /**
   * The button that initiates validation action
   */
  private final JButton myButton;

  /**
   * A constructor from fields
   *
   * @param project       the project to use
   * @param bzrRoot       the git root directory
   * @param textField     the text field that contains object reference
   * @param button        the button that initiates validation action
   * @param statusChanged the action that is invoked when validation status changed
   */
  public BzrReferenceValidator(final Project project,
                               final JComboBox bzrRoot,
                               final JTextField textField,
                               final JButton button,
                               final Runnable statusChanged) {
    myProject = project;
    myBzrRoot = bzrRoot;
    myTextField = textField;
    myButton = button;
    myBzrRoot.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        myLastResult = false;
        myLastResultText = null;
      }
    });
    myTextField.getDocument().addDocumentListener(new DocumentAdapter() {
      protected void textChanged(final DocumentEvent e) {
        // note that checkOkButton is called in other listener
        myButton.setEnabled(myTextField.getText().trim().length() != 0);
      }
    });
    myButton.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final String revisionExpression = myTextField.getText();
        myLastResultText = revisionExpression;
        myLastResult = false;
        try {
          BzrRevisionNumber revision = BzrRevisionNumber.resolve(myProject, gitRoot(), revisionExpression);
          BzrUtil.showSubmittedFiles(myProject, revision.asString(), gitRoot(), false, false);
          myLastResult = true;
        }
        catch (VcsException ex) {
          BzrUIUtil.showOperationError(myProject, ex, "Validating revision: " + revisionExpression);
        }
        if (statusChanged != null) {
          statusChanged.run();
        }
      }
    });
    myButton.setEnabled(myTextField.getText().length() != 0);
  }

  /**
   * @return true if the reference is known to be invalid
   */
  public boolean isInvalid() {
    final String revisionExpression = myTextField.getText();
    return revisionExpression.equals(myLastResultText) && !myLastResult;
  }

  /**
   * @return currently selected git root
   */
  private VirtualFile gitRoot() {
    return (VirtualFile)myBzrRoot.getSelectedItem();
  }
}
