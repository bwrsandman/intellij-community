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

package bazaar4idea.diff;

import bazaar4idea.changes.BzrChangeUtils;
import bazaar4idea.commands.BzrCommand;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.TreeDiffProvider;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsFileUtil;
import com.intellij.vcsUtil.VcsUtil;
import bazaar4idea.BzrBranchesSearcher;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.util.StringScanner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class BzrTreeDiffProvider implements TreeDiffProvider {
  private final static Logger LOG = Logger.getInstance("#bazaar4idea.diff.BzrTreeDiffProvider");
  private final Project myProject;

  public BzrTreeDiffProvider(final Project project) {
    myProject = project;
  }

  public Collection<String> getRemotelyChanged(final VirtualFile vcsRoot, final Collection<String> paths) {
    try {
      final BzrBranchesSearcher searcher = new BzrBranchesSearcher(myProject, vcsRoot, true);
      if (searcher.getLocal() == null || searcher.getRemote() == null) return Collections.emptyList();
      ArrayList<String> rc = new ArrayList<String>();
      final Collection<FilePath> files = new ArrayList<FilePath>(paths.size());
      for (String path : paths) {
        files.add(VcsUtil.getFilePath(path));
      }
      for (List<String> pathList : VcsFileUtil.chunkPaths(vcsRoot, files)) {
        BzrSimpleHandler handler = new BzrSimpleHandler(myProject, vcsRoot, BzrCommand.DIFF);
        handler.addParameters("--name-status", "--diff-filter=ADCRUX", "-M", "HEAD..." + searcher.getRemote().getFullName());
        //handler.setSilent(true);
        handler.setStdoutSuppressed(true);
        handler.addParameters(pathList);
        String output = handler.run();
        Collection<String> pathCollection = BzrChangeUtils.parseDiffForPaths(vcsRoot.getPath(), new StringScanner(output));
        rc.addAll(pathCollection);
      }
      return rc;
    }
    catch (VcsException e) {
      LOG.info(e);
      return Collections.emptyList();
    }
  }
}
