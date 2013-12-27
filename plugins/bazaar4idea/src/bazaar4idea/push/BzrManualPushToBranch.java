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
package bazaar4idea.push;

import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.ListCellRendererWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.GridBag;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;

/**
 * @author Kirill Likhodedov
 */
class BzrManualPushToBranch extends JPanel {

  private final Collection<BzrRepository> myRepositories;

  private final JCheckBox myManualPush;
  private final JTextField myDestBranchTextField;
  private final JBLabel myComment;
  private final BzrPushLogRefreshAction myRefreshAction;
  private final JComponent myRefreshButton;
  private final RemoteSelector myRemoteSelector;
  private final JComponent myRemoteSelectorComponent;

  BzrManualPushToBranch(@NotNull Collection<BzrRepository> repositories, @NotNull final Runnable performOnRefresh) {
    super();
    myRepositories = repositories;

    myManualPush = new JCheckBox("Push current branch to alternative branch: ", false);
    myManualPush.setMnemonic('b');

    myDestBranchTextField = new JTextField(20);
    
    myComment = new JBLabel("This will apply to all selected repositories", UIUtil.ComponentStyle.SMALL);
    
    myRefreshAction = new BzrPushLogRefreshAction() {
      @Override public void actionPerformed(AnActionEvent e) {
        performOnRefresh.run();
      }
    };
    myRefreshButton = new ActionButton(myRefreshAction,  myRefreshAction.getTemplatePresentation(), myRefreshAction.getTemplatePresentation().getText(), ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
    myRefreshButton.setFocusable(true);
    final ShortcutSet shortcutSet = ActionManager.getInstance().getAction(IdeActions.ACTION_REFRESH).getShortcutSet();
    myRefreshAction.registerCustomShortcutSet(shortcutSet, myRefreshButton);

    myRemoteSelector = new RemoteSelector(getRemotesWithCommonNames(repositories));
    myRemoteSelectorComponent = myRemoteSelector.createComponent();

    setDefaultComponentsEnabledState(myManualPush.isSelected());
    myManualPush.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        boolean isManualPushSelected = myManualPush.isSelected();
        setDefaultComponentsEnabledState(isManualPushSelected);
        if (isManualPushSelected) {
          myDestBranchTextField.requestFocus();
          myDestBranchTextField.selectAll();
        }
      }
    });

    layoutComponents();
  }

  private void setDefaultComponentsEnabledState(boolean selected) {
    myDestBranchTextField.setEnabled(selected);
    myComment.setEnabled(selected);
    myRemoteSelector.setEnabled(selected);
  }

  private void layoutComponents() {
    JPanel panel = new JPanel();
    GridBagLayout layout = new GridBagLayout();
    panel.setLayout(layout);
    GridBag g = new GridBag()
      .setDefaultFill(GridBagConstraints.NONE)
      .setDefaultAnchor(GridBagConstraints.BASELINE_LEADING)
      .setDefaultWeightX(1, 1)
      .setDefaultInsets(new Insets(0, 0, UIUtil.DEFAULT_VGAP, 5))
    ;

    panel.add(myManualPush, g.nextLine().next());
    panel.add(myRemoteSelectorComponent, g.next());
    panel.add(myDestBranchTextField, g.next());
    panel.add(myRefreshButton, g.next());
    if (myRepositories.size() > 1) {
      panel.add(myComment, g.nextLine().insets(0, 28, 0, 0).coverLine());
    }

    setLayout(new BorderLayout());
    add(panel, BorderLayout.WEST);
  }

  boolean turnedOn() {
    return myManualPush.isSelected() && !myDestBranchTextField.getText().isEmpty();
  }

  @NotNull
  String getBranchToPush() {
    return myDestBranchTextField.getText();
  }
  
  void setBranchToPushIfNotSet(@NotNull String text) {
    if (myDestBranchTextField.getText().isEmpty()) {
      myDestBranchTextField.setText(text);
    }
  }

  @NotNull
  BzrRemote getSelectedRemote() {
    return myRemoteSelector.getSelectedValue();
  }

  public void selectRemote(String remoteName) {
    myRemoteSelector.selectRemote(remoteName);
  }

  @NotNull
  public static Collection<BzrRemote> getRemotesWithCommonNames(@NotNull Collection<BzrRepository> repositories) {
    if (repositories.isEmpty()) {
      return Collections.emptyList();
    }
    Iterator<BzrRepository> iterator = repositories.iterator();
    List<BzrRemote> commonRemotes = new ArrayList<BzrRemote>(iterator.next().getRemotes());
    while (iterator.hasNext()) {
      BzrRepository repository = iterator.next();
      Collection<String> remoteNames = getRemoteNames(repository);
      for (Iterator<BzrRemote> commonIter = commonRemotes.iterator(); commonIter.hasNext(); ) {
        BzrRemote remote = commonIter.next();
        if (!remoteNames.contains(remote.getName())) {
          commonIter.remove();
        } 
      }
    }
    return commonRemotes;
  }

  @NotNull
  private static Collection<String> getRemoteNames(@NotNull BzrRepository repository) {
    Collection<String> names = new ArrayList<String>(repository.getRemotes().size());
    for (BzrRemote remote : repository.getRemotes()) {
      names.add(remote.getName());
    }
    return names;
  }

  /**
   * Component to select remotes.
   * Just a JCombobox actually, but more flexible: if there is only one remote, we could use JLabel or something like that.
   */
  private static class RemoteSelector {

    private final Collection<BzrRemote> myRemotes;
    private JComboBox myRemoteCombobox;

    private RemoteSelector(@NotNull Collection<BzrRemote> remotes) {
      myRemotes = remotes;
    }

    @NotNull
    JComponent createComponent() {
      myRemoteCombobox = new JComboBox();
      myRemoteCombobox.setRenderer(new RemoteCellRenderer(myRemoteCombobox.getRenderer()));
      for (BzrRemote remote : myRemotes) {
        myRemoteCombobox.addItem(remote);
      }
      myRemoteCombobox.setToolTipText("Select remote");
      if (myRemotes.size() == 1) {
        myRemoteCombobox.setEnabled(false);
      }
      return myRemoteCombobox;
    }

    @NotNull
    BzrRemote getSelectedValue() {
      return (BzrRemote)myRemoteCombobox.getSelectedItem();
    }

    void setEnabled(boolean selected) {
      if (myRemotes.size() > 1) {
        myRemoteCombobox.setEnabled(selected);
      }
    }

    public void selectRemote(@NotNull String remoteName) {
      for (BzrRemote remote : myRemotes) {
        if (remote.getName().equals(remoteName)) {
          myRemoteCombobox.setSelectedItem(remote);
          return;
        }
      }
      myRemoteCombobox.setSelectedIndex(0);
    }

    private static class RemoteCellRenderer extends ListCellRendererWrapper {
      public RemoteCellRenderer(final ListCellRenderer listCellRenderer) {
        super();
      }

      @Override
      public void customize(JList list, Object value, int index, boolean selected, boolean hasFocus) {
        if (value instanceof BzrRemote) {
          setText(((BzrRemote)value).getName());
        }
      }
    }

  }

  private abstract static class BzrPushLogRefreshAction extends DumbAwareAction {
    
    BzrPushLogRefreshAction() {
      super("Refresh commit list", "Refresh commit list", AllIcons.Actions.Refresh);
    }
  }
  
}
