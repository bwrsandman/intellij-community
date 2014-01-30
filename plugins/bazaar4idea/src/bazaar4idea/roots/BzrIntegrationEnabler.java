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
package bazaar4idea.roots;

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrVcs;
import bazaar4idea.commands.BzrCommandResult;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.roots.VcsRootErrorsFinder;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import bazaar4idea.Notificator;
import bazaar4idea.commands.Bzr;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.intellij.dvcs.DvcsUtil.joinRootsPaths;
import static com.intellij.openapi.util.text.StringUtil.pluralize;

/**
 * @author Kirill Likhodedov
 */
public class BzrIntegrationEnabler {

  private final @NotNull Project myProject;
  private final @NotNull Bzr myBzr;
  private final @NotNull BzrPlatformFacade myPlatformFacade;

  private static final Logger LOG = Logger.getInstance(BzrIntegrationEnabler.class);

  public BzrIntegrationEnabler(@NotNull Project project, @NotNull Bzr bzr, @NotNull BzrPlatformFacade platformFacade) {
    myProject = project;
    myBzr = bzr;
    myPlatformFacade = platformFacade;
  }

  public void enable(@NotNull Collection<VcsRoot> vcsRoots) {
    Notificator notificator = myPlatformFacade.getNotificator(myProject);
    Collection<VcsRoot> bzrRoots = ContainerUtil.filter(vcsRoots, new Condition<VcsRoot>() {
      @Override
      public boolean value(VcsRoot root) {
        AbstractVcs vcs = root.getVcs();
        return vcs != null && vcs.getName().equals(BzrVcs.NAME);
      }
    });
    Collection<VirtualFile> roots = VcsRootErrorsFinder.vcsRootsToVirtualFiles(bzrRoots);
    VirtualFile projectDir = myProject.getBaseDir();
    assert projectDir != null : "Base dir is unexpectedly null for project: " + myProject;

    if (bzrRoots.isEmpty()) {
      boolean succeeded = bzrInitOrNotifyError(notificator, projectDir);
      if (succeeded) {
        addVcsRoots(Collections.singleton(projectDir));
      }
    }
    else {
      assert !roots.isEmpty();
      if (roots.size() > 1 || isProjectBelowVcs(roots)) {
        notifyAddedRoots(notificator, roots);
      }
      addVcsRoots(roots);
    }
  }

  private boolean isProjectBelowVcs(@NotNull Collection<VirtualFile> bzrRoots) {
    //check if there are vcs roots strictly above the project dir
    VirtualFile baseDir = myProject.getBaseDir();
    for (VirtualFile root : bzrRoots) {
      if (VfsUtilCore.isAncestor(root, baseDir, true)) {
        return true;
      }
    }
    return false;
  }

  private static void notifyAddedRoots(Notificator notificator, Collection<VirtualFile> roots) {
    notificator.notifySuccess("", String.format("Added Bazaar %s: %s", pluralize("root", roots.size()), joinRootsPaths(roots)));
  }

  private boolean bzrInitOrNotifyError(@NotNull Notificator notificator, @NotNull final VirtualFile projectDir) {
    BzrCommandResult result = myBzr.init(myProject, projectDir);
    if (result.success()) {
      refreshBzrDir(projectDir);
      notificator.notifySuccess("", "Created Bazaar repository in " + projectDir.getPresentableUrl());
      return true;
    }
    else {
      if (((BzrVcs)myPlatformFacade.getVcs(myProject)).getExecutableValidator().checkExecutableAndNotifyIfNeeded()) {
        notificator.notifyError("Couldn't bzr init " + projectDir.getPresentableUrl(), result.getErrorOutputAsHtmlString());
        LOG.info(result.getErrorOutputAsHtmlString());
      }
      return false;
    }
  }

  private void refreshBzrDir(final VirtualFile projectDir) {
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        myPlatformFacade.runReadAction(new Runnable() {
          @Override
          public void run() {
            myPlatformFacade.getLocalFileSystem().refreshAndFindFileByPath(projectDir.getPath() + "/.bzr");
          }
        });
      }
    });
  }

  private void addVcsRoots(@NotNull Collection<VirtualFile> roots) {
    ProjectLevelVcsManager vcsManager = myPlatformFacade.getVcsManager(myProject);
    AbstractVcs vcs = myPlatformFacade.getVcs(myProject);
    List<VirtualFile> currentBzrRoots = Arrays.asList(vcsManager.getRootsUnderVcs(vcs));

    List<VcsDirectoryMapping> mappings = new ArrayList<VcsDirectoryMapping>(vcsManager.getDirectoryMappings(vcs));

    for (VirtualFile root : roots) {
      if (!currentBzrRoots.contains(root)) {
        mappings.add(new VcsDirectoryMapping(root.getPath(), vcs.getName()));
      }
    }
    vcsManager.setDirectoryMappings(mappings);
  }
}
