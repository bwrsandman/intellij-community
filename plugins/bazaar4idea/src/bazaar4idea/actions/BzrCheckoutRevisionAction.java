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

import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.vcs.log.VcsFullCommitDetails;
import bazaar4idea.branch.BzrBrancher;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

public class BzrCheckoutRevisionAction extends BzrLogSingleCommitAction {

  @Override
  protected void actionPerformed(@NotNull BzrRepository repository, @NotNull VcsFullCommitDetails commit) {
    BzrBrancher brancher = ServiceManager.getService(repository.getProject(), BzrBrancher.class);
    brancher.checkout(commit.getHash().asString(), Collections.singletonList(repository), null);
  }

}
