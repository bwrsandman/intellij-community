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
package bazaar4idea.push;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrCommit;
import bazaar4idea.BzrUtil;
import bazaar4idea.repo.BzrRepository;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.TypeSafeDataProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.issueLinks.IssueLinkHtmlRenderer;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowser;
import com.intellij.ui.*;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The list of commits from multiple repositories and branches, with diff panel at the right.
 *
 * @author Kirill Likhodedov
 */
class BzrPushLog extends JPanel implements TypeSafeDataProvider {

  private final Project myProject;
  private final Collection<BzrRepository> myAllRepositories;
  private final ChangesBrowser myChangesBrowser;
  private final CheckboxTree myTree;
  private final DefaultTreeModel myTreeModel;
  private final CheckedTreeNode myRootNode;
  private final ReentrantReadWriteLock TREE_CONSTRUCTION_LOCK = new ReentrantReadWriteLock();
  private boolean myTreeWasConstructed;
  private final MyTreeCellRenderer myTreeCellRenderer;

  BzrPushLog(@NotNull Project project, @NotNull Collection<BzrRepository> repositories, @NotNull final Consumer<Boolean> checkboxListener) {
    myProject = project;
    myAllRepositories = repositories;

    myRootNode = new CheckedTreeNode(null);
    myRootNode.add(new DefaultMutableTreeNode(new FakeCommit()));

    myTreeModel = new DefaultTreeModel(myRootNode);
    myTreeCellRenderer = new MyTreeCellRenderer();
    myTree = new CheckboxTree(myTreeCellRenderer, myRootNode) {
      @Override
      protected void onNodeStateChanged(CheckedTreeNode node) {
        Object userObject = node.getUserObject();
        if (userObject instanceof BzrRepository) {
          checkboxListener.consume(node.isChecked());
        }
      }

      @Override
      public String getToolTipText(MouseEvent event) {
        final TreePath path = myTree.getPathForLocation(event.getX(), event.getY());
        if (path == null) {
          return "";
        }
        Object node = path.getLastPathComponent();
        if (node == null || (!(node instanceof DefaultMutableTreeNode))) {
          return "";
        }
        Object userObject = ((DefaultMutableTreeNode)node).getUserObject();
        if (userObject instanceof BzrCommit) {
          BzrCommit commit = (BzrCommit)userObject;
          return getHashString(commit) + "  " + getDateString(commit) + "  by " + commit.getAuthor().getName() + "\n\n" +
                 IssueLinkHtmlRenderer.formatTextWithLinks(myProject, commit.getFullMessage());
        }
        return "";
      }
    };
    myTree.setRootVisible(false);
    TreeUtil.expandAll(myTree);

    myTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    myTree.addTreeSelectionListener(new TreeSelectionListener() {
      @Override
      public void valueChanged(TreeSelectionEvent e) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) myTree.getLastSelectedPathComponent();
        if (node != null) {
          Object nodeInfo = node.getUserObject();
          if (nodeInfo instanceof BzrCommit) {
            myChangesBrowser.getViewer().setEmptyText("No differences");
            myChangesBrowser.setChangesToDisplay(new ArrayList<Change>(((BzrCommit)nodeInfo).getChanges()));
            return;
          }
        }
        setDefaultEmptyText();
        myChangesBrowser.setChangesToDisplay(Collections.<Change>emptyList());
      }
    });
    ToolTipManager.sharedInstance().registerComponent(myTree);

    myChangesBrowser = new ChangesBrowser(project, null, Collections.<Change>emptyList(), null, false, true, null, ChangesBrowser.MyUseCase.LOCAL_CHANGES, null);
    myChangesBrowser.getDiffAction().registerCustomShortcutSet(CommonShortcuts.getDiff(), myTree);
    setDefaultEmptyText();

    Splitter splitter = new Splitter(false, 0.7f);
    splitter.setFirstComponent(ScrollPaneFactory.createScrollPane(myTree));
    splitter.setSecondComponent(myChangesBrowser);
    
    setLayout(new BorderLayout());
    add(splitter);
  }

  private void setDefaultEmptyText() {
    myChangesBrowser.getViewer().setEmptyText("No commits selected");
  }

  // Make changes available for diff action
  @Override
  public void calcData(DataKey key, DataSink sink) {
    if (VcsDataKeys.CHANGES.equals(key)) {
      DefaultMutableTreeNode[] selectedNodes = myTree.getSelectedNodes(DefaultMutableTreeNode.class, null);
      if (selectedNodes.length == 0) {
        return;
      }
      Object object = selectedNodes[0].getUserObject();
      if (object instanceof BzrCommit) {
        sink.put(key, ArrayUtil.toObjectArray(((BzrCommit)object).getChanges(), Change.class));
      }
    }
  }

  @NotNull
  public JComponent getPreferredFocusComponent() {
    return myTree;
  }

  void setCommits(@NotNull BzrCommitsByRepoAndBranch commits) {
    try {
      TREE_CONSTRUCTION_LOCK.writeLock().lock();
      myRootNode.removeAllChildren();
      createNodes(commits);
      myTreeModel.nodeStructureChanged(myRootNode);
      myTree.setModel(myTreeModel);  // TODO: why doesn't it repaint otherwise?
      TreeUtil.expandAll(myTree);
      selectFirstCommit();
      collapseEmptyRepoNodes(commits);
      myTreeWasConstructed = true;
    }
    finally {
      TREE_CONSTRUCTION_LOCK.writeLock().unlock();
    }
  }

  private void selectFirstCommit() {
    DefaultMutableTreeNode firstLeaf = myRootNode.getFirstLeaf();
    if (firstLeaf == null) {
      return;
    }

    Enumeration enumeration = myRootNode.depthFirstEnumeration();
    DefaultMutableTreeNode node = null;
    while (enumeration.hasMoreElements()) {
      node = (DefaultMutableTreeNode) enumeration.nextElement();
      if (node.isLeaf() && node.getUserObject() instanceof BzrCommit) {
        break;
      }
    }
    if (node == null) {
      node = firstLeaf;
    }
    myTree.setSelectionPath(new TreePath(node.getPath()));
  }

  private void collapseEmptyRepoNodes(BzrCommitsByRepoAndBranch commits) {
    Enumeration enumeration = myRootNode.breadthFirstEnumeration();
    while (enumeration.hasMoreElements()) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) enumeration.nextElement();
      Object userObject = node.getUserObject();
      if (userObject == null) {
        // root object => seeking further
      }
      else if (userObject instanceof BzrRepository) {
        if (commits.get((BzrRepository)userObject).isEmpty()) {
          myTree.collapsePath(new TreePath(node.getPath()));
        }
      }
      else {
        // we're looking to the breadthFirstEnumeration => all repository nodes have already been enumerated
        return;
      }
    }
  }

  private void createNodes(@NotNull BzrCommitsByRepoAndBranch commits) {
    for (BzrRepository repository : sortRepositories(commits)) {
      BzrCommitsByBranch commitsByBranch = commits.get(repository);
      createRepoNode(repository, commitsByBranch, myRootNode);
    }
  }

  @NotNull
  private static List<BzrRepository> sortRepositories(@NotNull final BzrCommitsByRepoAndBranch commits) {
    List<BzrRepository> repos = new ArrayList<BzrRepository>(commits.getRepositories());
    Collections.sort(repos, new Comparator<BzrRepository>() {
      @Override public int compare(BzrRepository r1, BzrRepository r2) {
        // empty repositories - to the end
        if (commits.get(r1).isEmpty() && !commits.get(r2).isEmpty()) {
          return 1;
        }
        if (commits.get(r2).isEmpty() && !commits.get(r1).isEmpty()) {
          return -1;
        }
        return r1.getPresentableUrl().compareTo(r2.getPresentableUrl());
      }
    });
    return repos;
  }

  /**
   * Creates the node with subnodes for a repository and adds it to the rootNode.
   * If there is only one repo in the project, doesn't create a node for the repository, and adds subnodes directly to the rootNode.
   */
  private void createRepoNode(@NotNull BzrRepository repository, @NotNull BzrCommitsByBranch commitsByBranch,
                              @NotNull DefaultMutableTreeNode rootNode) {
    DefaultMutableTreeNode parentNode;
    if (BzrUtil.justOneBzrRepository(myProject)) {
      parentNode = rootNode;
    } else {
      parentNode = new CheckedTreeNode(repository);
      if (commitsByBranch.isEmpty()) {
        ((CheckedTreeNode)parentNode).setChecked(false);
      }
      rootNode.add(parentNode);
    }

    for (BzrBranch branch : sortBranches(commitsByBranch.getBranches())) {
      DefaultMutableTreeNode branchNode = createBranchNode(branch, commitsByBranch.get(branch));
      parentNode.add(branchNode);
    }
  }

  private static List<BzrBranch> sortBranches(@NotNull Collection<BzrBranch> branches) {
    List<BzrBranch> sortedBranches = new ArrayList<BzrBranch>(branches);
    Collections.sort(sortedBranches, new Comparator<BzrBranch>() {
      @Override public int compare(BzrBranch o1, BzrBranch o2) {
        return o1.getName().compareTo(o2.getName());
      }
    });
    return sortedBranches;
  }

  private static DefaultMutableTreeNode createBranchNode(@NotNull BzrBranch branch, @NotNull BzrPushBranchInfo branchInfo) {
    DefaultMutableTreeNode branchNode = new DefaultMutableTreeNode(branchInfo);
    for (BzrCommit commit : branchInfo.getCommits()) {
      branchNode.add(new DefaultMutableTreeNode(commit));
    }
    if (branchInfo.isNewBranchCreated()) {
      branchNode.add(new DefaultMutableTreeNode(new MoreCommitsToShow()));
    }
    return branchNode;
  }

  void displayError(String message) {
    DefaultMutableTreeNode titleNode = new DefaultMutableTreeNode("Error: couldn't collect commits to be pushed");
    DefaultMutableTreeNode detailNode = new DefaultMutableTreeNode(message);
    myRootNode.add(titleNode);
    myRootNode.add(detailNode);
    myTreeModel.reload(myRootNode);
    TreeUtil.expandAll(myTree);
    repaint();
  }

  /**
   * @return repositories selected (via checkboxes) to be pushed.
   */
  Collection<BzrRepository> getSelectedRepositories() {
    if (myAllRepositories.size() == 1) {
      return myAllRepositories;
    }

    try {
      TREE_CONSTRUCTION_LOCK.readLock().lock();  // wait for tree to be constructed
      if (!myTreeWasConstructed) {
        return myAllRepositories;
      }
      else {
        Collection<BzrRepository> selectedRepositories = new ArrayList<BzrRepository>(myAllRepositories.size());
        if (myRootNode.getChildCount() == 0) {  // the method is requested before tree construction began => returning all repos.
          return myAllRepositories;
        }

        for (int i = 0; i < myRootNode.getChildCount(); i++) {
          TreeNode child = myRootNode.getChildAt(i);
          if (child instanceof CheckedTreeNode) {
            CheckedTreeNode node = (CheckedTreeNode)child;
            if (node.isChecked()) {
              if (node.getUserObject() instanceof BzrRepository) {
                selectedRepositories.add((BzrRepository)node.getUserObject());
              }
            }
          }
        }
        return selectedRepositories;
      }
    }
    finally {
      TREE_CONSTRUCTION_LOCK.readLock().unlock();
    }
  }

  @NotNull
  private static String getDateString(@NotNull BzrCommit commit) {
    return DateFormatUtil.formatPrettyDateTime(commit.getTime()) + " ";
  }

  @NotNull
  private static String getHashString(@NotNull BzrCommit commit) {
    return BzrUtil.getShortHash(commit.getHash().toString());
  }

  private static class MyTreeCellRenderer extends CheckboxTree.CheckboxTreeCellRenderer {

    @Override
    public void customizeRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
      Object userObject;
      if (value instanceof CheckedTreeNode) {
        userObject = ((CheckedTreeNode)value).getUserObject();
      } else if (value instanceof DefaultMutableTreeNode) {
        userObject = ((DefaultMutableTreeNode)value).getUserObject();
      } else {
        return;
      }

      ColoredTreeCellRenderer renderer = getTextRenderer();
      if (userObject instanceof BzrCommit) {
        BzrCommit commit = (BzrCommit)userObject;
        renderer.append(commit.getSubject(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, getTextRenderer().getForeground()));
        renderer.setToolTipText(getHashString(commit) + " " + getDateString(commit));
      }
      else if (userObject instanceof BzrRepository) {
        String repositoryPath = DvcsUtil.getShortRepositoryName((BzrRepository)userObject);
        renderer.append(repositoryPath, SimpleTextAttributes.GRAY_ATTRIBUTES);
      }
      else if (userObject instanceof BzrPushBranchInfo) {
        BzrPushBranchInfo branchInfo = (BzrPushBranchInfo) userObject;
        BzrBranch fromBranch = branchInfo.getSourceBranch();
        BzrBranch dest = branchInfo.getDestBranch();

        BzrPushBranchInfo.Type type = branchInfo.getType();
        final String showingRecentCommits = ", showing " + BzrPusher.RECENT_COMMITS_NUMBER + " recent commits";
        String text = fromBranch.getName();
        SimpleTextAttributes attrs = SimpleTextAttributes.REGULAR_ATTRIBUTES;
        String additionalText = "";
        switch (type) {
          case STANDARD:
            text += " -> " + dest.getName();
            if (branchInfo.getCommits().isEmpty()) {
              additionalText = " nothing to push";
            }
            break;
          case NEW_BRANCH:
            text += " -> +" + dest.getName();
            attrs = SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES;
            additionalText = " new branch will be created" + showingRecentCommits;
            break;
          case NO_TRACKED_OR_TARGET:
            attrs = SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES;
            additionalText = " no tracked branch. Use checkbox below to push branch to manually specified" + showingRecentCommits;
            break;
        }
        renderer.append(text, attrs);
        renderer.append(additionalText, new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, UIUtil.getInactiveTextColor()));
      }
      else if (userObject instanceof FakeCommit) {
        int spaces = 6 + 15 + 3 + 30;
        String s = String.format("%" + spaces + "s", " ");
        renderer.append(s, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, renderer.getBackground()));
      }
      else if (userObject instanceof MoreCommitsToShow) {
        renderer.append("...");
      }
      else {
        renderer.append(userObject == null ? "" : userObject.toString());
      }
    }
  }
  
  private static class FakeCommit {
  }

  private static class MoreCommitsToShow {
  }
}
