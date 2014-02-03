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
package bazaar4idea.config;

import bazaar4idea.BzrVcs;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.repo.BzrRepositoryManager;
import bazaar4idea.ui.branch.BzrBranchSyncSetting;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Bazaar VCS configuration panel
 */
public class BzrVcsPanel {

  private static final String IDEA_SSH = BzrBundle.getString("bzr.vcs.config.ssh.mode.idea"); // IDEA ssh value
  private static final String NATIVE_SSH = BzrBundle.getString("bzr.vcs.config.ssh.mode.native"); // Native SSH value

  private final BzrVcsApplicationSettings myAppSettings;
  private final BzrVcs myVcs;

  private JButton myTestButton; // Test git executable
  private JComponent myRootPanel;
  private TextFieldWithBrowseButton myBzrField;
  private JComboBox mySSHExecutableComboBox; // Type of SSH executable to use
  private JCheckBox myAutoUpdateIfPushRejected;
  private JBCheckBox mySyncBranchControl;
  private JBCheckBox myWarnAboutCrlf;

  public BzrVcsPanel(@NotNull Project project) {
    myVcs = BzrVcs.getInstance(project);
    myAppSettings = BzrVcsApplicationSettings.getInstance();
    mySSHExecutableComboBox.addItem(IDEA_SSH);
    mySSHExecutableComboBox.addItem(NATIVE_SSH);
    mySSHExecutableComboBox.setSelectedItem(IDEA_SSH);
    mySSHExecutableComboBox
      .setToolTipText(BzrBundle.message("bzr.vcs.config.ssh.mode.tooltip", ApplicationNamesInfo.getInstance().getFullProductName()));
    myTestButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        testConnection();
      }
    });
    myBzrField.addBrowseFolderListener(BzrBundle.getString("find.bzr.title"), BzrBundle.getString("find.bzr.description"), project,
                                       FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor());
    final BzrRepositoryManager repositoryManager = ServiceManager.getService(project, BzrRepositoryManager.class);
    mySyncBranchControl.setVisible(repositoryManager != null && repositoryManager.moreThanOneRoot());
  }

  /**
   * Test availability of the connection
   */
  private void testConnection() {
    final String executable = getCurrentExecutablePath();
    if (myAppSettings != null) {
      myAppSettings.setPathToBzr(executable);
    }
    final BzrVersion version;
    try {
      version = BzrVersion.identifyVersion(executable);
    } catch (Exception e) {
      Messages.showErrorDialog(myRootPanel, e.getMessage(), BzrBundle.getString("find.bzr.error.title"));
      return;
    }

    if (version.isSupported()) {
      Messages.showInfoMessage(myRootPanel,
                               String.format("<html>%s<br>Bazaar version is %s</html>", BzrBundle.getString("find.bzr.success.title"),
                                             version.toString()),
                               BzrBundle.getString("find.bzr.success.title"));
    } else {
      Messages.showWarningDialog(myRootPanel, BzrBundle.message("find.bzr.unsupported.message", version.toString()),
                                 BzrBundle.getString("find.bzr.success.title"));
    }
  }

  private String getCurrentExecutablePath() {
    return myBzrField.getText().trim();
  }

  /**
   * @return the configuration panel
   */
  public JComponent getPanel() {
    return myRootPanel;
  }

  /**
   * Load settings into the configuration panel
   *
   * @param settings the settings to load
   */
  public void load(@NotNull BzrVcsSettings settings) {
    myBzrField.setText(settings.getAppSettings().getPathToBzr());
    mySSHExecutableComboBox.setSelectedItem(settings.isIdeaSsh() ? IDEA_SSH : NATIVE_SSH);
    myAutoUpdateIfPushRejected.setSelected(settings.autoUpdateIfPushRejected());
    mySyncBranchControl.setSelected(settings.getSyncSetting() == BzrBranchSyncSetting.SYNC);
    myWarnAboutCrlf.setSelected(settings.warnAboutCrlf());
  }

  /**
   * Check if fields has been modified with respect to settings object
   *
   * @param settings the settings to load
   */
  public boolean isModified(@NotNull BzrVcsSettings settings) {
    return !settings.getAppSettings().getPathToBzr().equals(getCurrentExecutablePath()) ||
           (settings.isIdeaSsh() != IDEA_SSH.equals(mySSHExecutableComboBox.getSelectedItem())) ||
           !settings.autoUpdateIfPushRejected() == myAutoUpdateIfPushRejected.isSelected() ||
           ((settings.getSyncSetting() == BzrBranchSyncSetting.SYNC) != mySyncBranchControl.isSelected() ||
           settings.warnAboutCrlf() != myWarnAboutCrlf.isSelected());
  }

  /**
   * Save configuration panel state into settings object
   *
   * @param settings the settings object
   */
  public void save(@NotNull BzrVcsSettings settings) {
    settings.getAppSettings().setPathToBzr(getCurrentExecutablePath());
    myVcs.checkVersion();
    settings.getAppSettings().setIdeaSsh(IDEA_SSH.equals(mySSHExecutableComboBox.getSelectedItem()) ?
                                         BzrVcsApplicationSettings.SshExecutable.IDEA_SSH :
                                         BzrVcsApplicationSettings.SshExecutable.NATIVE_SSH);
    settings.setAutoUpdateIfPushRejected(myAutoUpdateIfPushRejected.isSelected());

    settings.setSyncSetting(mySyncBranchControl.isSelected() ? BzrBranchSyncSetting.SYNC : BzrBranchSyncSetting.DONT);
    settings.setWarnAboutCrlf(myWarnAboutCrlf.isSelected());
  }

}
