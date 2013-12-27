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
package bazaar4idea.repo;

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrUtil;
import com.intellij.dvcs.repo.AbstractRepositoryManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kirill Likhodedov
 */
public class BzrRepositoryManager extends AbstractRepositoryManager<BzrRepository> {

  @NotNull private final BzrPlatformFacade myPlatformFacade;

  public BzrRepositoryManager(@NotNull Project project,
                              @NotNull BzrPlatformFacade platformFacade,
                              @NotNull ProjectLevelVcsManager vcsManager) {
    super(project, vcsManager, platformFacade.getVcs(project), BzrUtil.DOT_BZR);
    myPlatformFacade = platformFacade;
  }

  @NotNull
  @Override
  protected BzrRepository createRepository(@NotNull VirtualFile root) {
    return BzrRepositoryImpl.getFullInstance(root, myProject, myPlatformFacade, this);
  }
}
