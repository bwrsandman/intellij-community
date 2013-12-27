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
package bazaar4idea.merge;

import bazaar4idea.BzrPlatformFacade;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.commands.Bzr;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Conflict resolver that makes a merge commit after all conflicts are resolved.
 *
 * @author Kirill Likhodedov
 */
public class BzrMergeCommittingConflictResolver extends BzrConflictResolver {
  private final Collection<VirtualFile> myMergingRoots;
  private final boolean myRefreshAfterCommit;
  private final BzrMerger myMerger;

  public BzrMergeCommittingConflictResolver(Project project,
                                            @NotNull Bzr bzr,
                                            BzrMerger merger,
                                            Collection<VirtualFile> mergingRoots,
                                            Params params,
                                            boolean refreshAfterCommit) {
    super(project, bzr, ServiceManager.getService(BzrPlatformFacade.class), mergingRoots, params);
    myMerger = merger;
    myMergingRoots = mergingRoots;
    myRefreshAfterCommit = refreshAfterCommit;
  }

  @Override protected boolean proceedAfterAllMerged() throws VcsException {
    myMerger.mergeCommit(myMergingRoots);
    if (myRefreshAfterCommit) {
      for (VirtualFile root : myMergingRoots) {
        root.refresh(true, true);
      }
    }
    return true;
  }
}
