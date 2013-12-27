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
package bazaar4idea.update;

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrVcs;
import bazaar4idea.config.BzrVcsSettings;
import bazaar4idea.repo.BzrRepositoryManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.update.SequentialUpdatesContext;
import com.intellij.openapi.vcs.update.UpdateEnvironment;
import com.intellij.openapi.vcs.update.UpdateSession;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import static bazaar4idea.BzrUtil.*;

/**
 * Bazaar update environment implementation. The environment does
 * {@code bzr pull -v} for each vcs root. Rebase variant is detected
 * and processed as well.
 */
public class BzrUpdateEnvironment implements UpdateEnvironment {
  private final BzrVcs myVcs;
  private final Project myProject;
  private final BzrVcsSettings mySettings;
  @NotNull private final BzrPlatformFacade myPlatformFacade;

  private static final Logger LOG = Logger.getInstance(BzrUpdateEnvironment.class);

  public BzrUpdateEnvironment(@NotNull Project project, @NotNull BzrVcs vcs, BzrVcsSettings settings) {
    myVcs = vcs;
    myProject = project;
    mySettings = settings;
    myPlatformFacade = ServiceManager.getService(project, BzrPlatformFacade.class);
  }

  public void fillGroups(UpdatedFiles updatedFiles) {
    //unused, there are no custom categories yet
  }

  @NotNull
  public UpdateSession updateDirectories(@NotNull FilePath[] filePaths, UpdatedFiles updatedFiles, ProgressIndicator progressIndicator, @NotNull Ref<SequentialUpdatesContext> sequentialUpdatesContextRef) throws ProcessCanceledException {
    Set<VirtualFile> roots = bzrRoots(Arrays.asList(filePaths));
    BzrRepositoryManager repositoryManager = getRepositoryManager(myProject);
    final BzrUpdateProcess bzrUpdateProcess = new BzrUpdateProcess(myProject, myPlatformFacade,
                                                                   progressIndicator, getRepositoriesFromRoots(repositoryManager, roots),
                                                                   updatedFiles);
    boolean result = bzrUpdateProcess.update(BzrUpdateProcess.UpdateMethod.READ_FROM_SETTINGS).isSuccess();
    return new BzrUpdateSession(result);
  }


  public boolean validateOptions(Collection<FilePath> filePaths) {
    for (FilePath p : filePaths) {
      if (!isUnderBzr(p)) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  public Configurable createConfigurable(Collection<FilePath> files) {
    return new BzrUpdateConfigurable(mySettings);
  }

}
