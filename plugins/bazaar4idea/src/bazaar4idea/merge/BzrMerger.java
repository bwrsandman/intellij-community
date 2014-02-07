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
package bazaar4idea.merge;

import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.repo.BzrRepositoryFiles;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.BzrBranch;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;

/**
 *
 * @author Kirill Likhodedov
 */
public class BzrMerger {

  private final Project myProject;
  private final BzrVcs myVcs;

  public BzrMerger(Project project) {
    myProject = project;
    myVcs = BzrVcs.getInstance(project);
  }


  public Collection<VirtualFile> getMergingRoots() {
    final Collection<VirtualFile> mergingRoots = new HashSet<VirtualFile>();
    for (VirtualFile root : ProjectLevelVcsManager.getInstance(myProject).getRootsUnderVcs(myVcs)) {
      if (BzrMergeUtil.isMergeInProgress(root)) {
        mergingRoots.add(root);
      }
    }
    return mergingRoots;
  }

  public void mergeCommit(Collection<VirtualFile> roots) throws VcsException {
    for (VirtualFile root : roots) {
      mergeCommit(root);
    }
  }

  public void mergeCommit(VirtualFile root) throws VcsException {
    BzrSimpleHandler handler = new BzrSimpleHandler(myProject, root, BzrCommand.COMMIT);

    File gitDir = new File(VfsUtilCore.virtualToIoFile(root), BzrUtil.DOT_BZR);
    File messageFile = new File(gitDir, BzrRepositoryFiles.MERGE_MSG);
    if (!messageFile.exists()) {
      final BzrBranch branch = BzrBranchUtil.getCurrentBranch(myProject, root);
      final String branchName = branch != null ? branch.getName() : "";
      handler.addParameters("-m", "Merge branch '" + branchName + "' of " + root.getPresentableUrl() + " with conflicts.");
    } else {
      handler.addParameters("-F", messageFile.getAbsolutePath());
    }
    handler.run();
  }

}
