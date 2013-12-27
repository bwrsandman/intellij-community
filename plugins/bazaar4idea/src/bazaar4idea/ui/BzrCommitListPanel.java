/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

import bazaar4idea.BzrCommit;
import bazaar4idea.BzrUtil;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesTreeBrowser;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.table.TableView;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.ListTableModel;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * A table with the list of commits.
 *
 * @author Kirill Likhodedov
 */
public class BzrCommitListPanel extends JPanel implements TypeSafeDataProvider {

  private final List<BzrCommit> myCommits;
  private final TableView<BzrCommit> myTable;

  public BzrCommitListPanel(@NotNull List<BzrCommit> commits, @Nullable String emptyText) {
    myCommits = commits;

    myTable = new TableView<BzrCommit>();
    updateModel();
    myTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    myTable.setStriped(true);
    if (emptyText != null) {
      myTable.getEmptyText().setText(emptyText);
    }

    setLayout(new BorderLayout());
    add(ScrollPaneFactory.createScrollPane(myTable));
  }

  /**
   * Adds a listener that would be called once user selects a commit in the table.
   */
  public void addListSelectionListener(final @NotNull Consumer<BzrCommit> listener) {
    myTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
      public void valueChanged(final ListSelectionEvent e) {
        ListSelectionModel lsm = (ListSelectionModel)e.getSource();
        int i = lsm.getMaxSelectionIndex();
        int j = lsm.getMinSelectionIndex();
        if (i >= 0 && i == j) {
          listener.consume(myCommits.get(i));
        }
      }
    });
  }

  public void addListMultipleSelectionListener(final @NotNull Consumer<List<Change>> listener) {
    myTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
      public void valueChanged(final ListSelectionEvent e) {
        List<BzrCommit> commits = myTable.getSelectedObjects();

        final List<Change> changes = new ArrayList<Change>();
        // We need changes in asc order for zipChanges, and they are in desc order in Table
        ListIterator<BzrCommit> iterator = commits.listIterator(commits.size());
        while (iterator.hasPrevious()) {
          changes.addAll(iterator.previous().getChanges());
        }

        listener.consume(CommittedChangesTreeBrowser.zipChanges(changes));
      }
    });
  }

  /**
   * Registers the diff action which will be called when the diff shortcut is pressed in the table.
   */
  public void registerDiffAction(@NotNull AnAction diffAction) {
    diffAction.registerCustomShortcutSet(CommonShortcuts.getDiff(), myTable);
  }

  // Make changes available for diff action
  @Override
  public void calcData(DataKey key, DataSink sink) {
    if (VcsDataKeys.CHANGES.equals(key)) {
      int[] rows = myTable.getSelectedRows();
      if (rows.length != 1) return;
      int row = rows[0];

      BzrCommit bzrCommit = myCommits.get(row);
      // suppressing: inherited API
      //noinspection unchecked
      sink.put(key, ArrayUtil.toObjectArray(bzrCommit.getChanges(), Change.class));
    }
  }

  @NotNull
  public JComponent getPreferredFocusComponent() {
    return myTable;
  }

  public void clearSelection() {
    myTable.clearSelection();
  }

  public void setCommits(@NotNull List<BzrCommit> commits) {
    myCommits.clear();
    myCommits.addAll(commits);
    updateModel();
    myTable.repaint();
  }

  private void updateModel() {
    myTable.setModelAndUpdateColumns(new ListTableModel<BzrCommit>(generateColumnsInfo(myCommits), myCommits, 0));
  }

  @NotNull
  private ColumnInfo[] generateColumnsInfo(@NotNull List<BzrCommit> commits) {
    ItemAndWidth hash = new ItemAndWidth("", 0);
    ItemAndWidth author = new ItemAndWidth("", 0);
    ItemAndWidth time = new ItemAndWidth("", 0);
    for (BzrCommit commit : commits) {
      hash = getMax(hash, getHash(commit));
      author = getMax(author, getAuthor(commit));
      time = getMax(time, getTime(commit));
    }

    return new ColumnInfo[] {
    new BzrCommitColumnInfo("Hash", hash.myItem) {
      @Override
      public String valueOf(BzrCommit commit) {
        return getHash(commit);
      }
    },
    new ColumnInfo<BzrCommit, String>("Subject") {
      @Override
      public String valueOf(BzrCommit commit) {
        return commit.getSubject();
      }
    },
    new BzrCommitColumnInfo("Author", author.myItem) {
      @Override
      public String valueOf(BzrCommit commit) {
        return getAuthor(commit);
      }
    },
    new BzrCommitColumnInfo("Author time", time.myItem) {
      @Override
      public String valueOf(BzrCommit commit) {
        return getTime(commit);
      }
    }
    };
  }

  private ItemAndWidth getMax(ItemAndWidth current, String candidate) {
    int width = myTable.getFontMetrics(myTable.getFont()).stringWidth(candidate);
    if (width > current.myWidth) {
      return new ItemAndWidth(candidate, width);
    }
    return current;
  }

  private static class ItemAndWidth {
    private final String myItem;
    private final int myWidth;

    private ItemAndWidth(String item, int width) {
      myItem = item;
      myWidth = width;
    }
  }

  private static String getHash(BzrCommit commit) {
    return BzrUtil.getShortHash(commit.getHash().toString());
  }

  private static String getAuthor(BzrCommit commit) {
    return commit.getAuthor().getName();
  }

  private static String getTime(BzrCommit commit) {
    return DateFormatUtil.formatPrettyDateTime(commit.getTime());
  }

  private abstract static class BzrCommitColumnInfo extends ColumnInfo<BzrCommit, String> {

    @NotNull private final String myMaxString;

    public BzrCommitColumnInfo(@NotNull String name, @NotNull String maxString) {
      super(name);
      myMaxString = maxString;
    }

    @Override
    public String getMaxStringValue() {
      return myMaxString;
    }

    @Override
    public int getAdditionalWidth() {
      return UIUtil.DEFAULT_HGAP;
    }
  }

}
