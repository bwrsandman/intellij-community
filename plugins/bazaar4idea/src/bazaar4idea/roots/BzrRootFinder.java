package bazaar4idea.roots;

import bazaar4idea.BzrVcs;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.VcsRootFinder;
import com.intellij.openapi.vcs.roots.VcsRootDetectInfo;
import com.intellij.openapi.vcs.roots.VcsRootDetector;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtilRt;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;

/**
 * @author Denis Zhdanov
 * @since 7/18/13 6:02 PM
 */
public class BzrRootFinder implements VcsRootFinder {

  @NotNull private final Project myProject;

  public BzrRootFinder(@NotNull Project project) {
    myProject = project;
  }

  @NotNull
  @Override
  public Collection<VcsDirectoryMapping> findRoots(@NotNull VirtualFile root) {
    VcsRootDetectInfo info = new VcsRootDetector(myProject).detect(root);
    Collection<VcsRoot> roots = info.getRoots();
    if (roots.isEmpty()) {
      return Collections.emptyList();
    }
    Collection<VcsDirectoryMapping> result = ContainerUtilRt.newArrayList();
    for (VcsRoot vcsRoot : roots) {
      VirtualFile vFile = vcsRoot.getPath();
      if (vFile != null) {
        result.add(new VcsDirectoryMapping(vFile.getPath(), BzrVcs.getKey().getName()));
      }
    }
    return result;
  }
}
