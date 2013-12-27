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
package bazaar4idea.update;

import bazaar4idea.config.BzrVcsSettings;
import bazaar4idea.i18n.BzrBundle;
import com.intellij.openapi.application.ApplicationNamesInfo;
import bazaar4idea.config.UpdateMethod;

import javax.swing.*;

/**
 * Update options panel
 */
public class BzrUpdateOptionsPanel {
  private JPanel myPanel;
  private JRadioButton myBranchDefaultRadioButton;
  private JRadioButton myForceRebaseRadioButton;
  private JRadioButton myForceMergeRadioButton;
  private JRadioButton myStashRadioButton;
  private JRadioButton myShelveRadioButton;

  public JComponent getPanel() {
    return myPanel;
  }

  public boolean isModified(BzrVcsSettings settings) {
    UpdateMethod type = getUpdateType();
    return type != settings.getUpdateType() || updateSaveFilesPolicy() != settings.updateChangesPolicy();
  }

  /**
   * @return get policy value from selected radio buttons
   */
  private BzrVcsSettings.UpdateChangesPolicy updateSaveFilesPolicy() {
    return UpdatePolicyUtils.getUpdatePolicy(myStashRadioButton, myShelveRadioButton);
  }

  /**
   * @return get the currently selected update type
   */
  private UpdateMethod getUpdateType() {
    UpdateMethod type = null;
    if (myForceRebaseRadioButton.isSelected()) {
      type = UpdateMethod.REBASE;
    }
    else if (myForceMergeRadioButton.isSelected()) {
      type = UpdateMethod.MERGE;
    }
    else if (myBranchDefaultRadioButton.isSelected()) {
      type = UpdateMethod.BRANCH_DEFAULT;
    }
    assert type != null;
    return type;
  }

  /**
   * Save configuration to settings object
   */
  public void applyTo(BzrVcsSettings settings) {
    settings.setUpdateType(getUpdateType());
    settings.setUpdateChangesPolicy(updateSaveFilesPolicy());
  }

  /**
   * Update panel according to settings
   */
  public void updateFrom(BzrVcsSettings settings) {
    switch (settings.getUpdateType()) {
      case REBASE:
        myForceRebaseRadioButton.setSelected(true);
        break;
      case MERGE:
        myForceMergeRadioButton.setSelected(true);
        break;
      case BRANCH_DEFAULT:
        myBranchDefaultRadioButton.setSelected(true);
        break;
      default:
        assert false : "Unknown value of update type: " + settings.getUpdateType();
    }
    UpdatePolicyUtils.updatePolicyItem(settings.updateChangesPolicy(), myStashRadioButton, myShelveRadioButton);
  }

  private void createUIComponents() {
    myShelveRadioButton = new JRadioButton(BzrBundle.message("update.options.save.shelve"));
    myShelveRadioButton.setToolTipText(
      BzrBundle.message("update.options.save.shelve.tooltip", ApplicationNamesInfo.getInstance().getFullProductName()));
  }
}
