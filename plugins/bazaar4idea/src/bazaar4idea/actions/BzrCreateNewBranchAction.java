/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package bazaar4idea.actions;

import bazaar4idea.branch.BzrBranchUtil;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.vcs.log.VcsFullCommitDetails;
import bazaar4idea.branch.BzrBrancher;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

public class BzrCreateNewBranchAction extends BzrLogSingleCommitAction {

  @Override
  protected void actionPerformed(@NotNull BzrRepository repository, @NotNull VcsFullCommitDetails commit) {
    Project project = repository.getProject();
    String reference = commit.getHash().asString();
    final String name = BzrBranchUtil
      .getNewBranchNameFromUser(project, Collections.singleton(repository), "Checkout New Branch From " + reference);
    if (name != null) {
      BzrBrancher brancher = ServiceManager.getService(project, BzrBrancher.class);
      brancher.checkoutNewBranchStartingFrom(name, reference, Collections.singletonList(repository), null);
    }
  }
}
