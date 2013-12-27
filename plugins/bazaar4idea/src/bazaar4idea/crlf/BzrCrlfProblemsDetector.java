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
package bazaar4idea.crlf;

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrUtil;
import bazaar4idea.attributes.BzrAttribute;
import bazaar4idea.attributes.BzrCheckAttrParser;
import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Given a number of files, detects if CRLF line separators in them are about to be committed to Bazaar. That is:
 * <ul>
 *   <li>Checks if {@code core.autocrlf} is set to {@code true} or {@code input}.</li>
 *   <li>If not, checks if files contain CRLFs.</li>
 *   <li>
 *     For files with CRLFs checks if there are gitattributes set on them, such that would either force CRLF conversion on checkin,
 *     either indicate that these CRLFs are here intentionally.
 *   </li>
 * </ul>
 * All checks are made only for Windows system.
 *
 * Everywhere in the detection process we fail gracefully in case of exceptions: we log the fact, but don't fail totally, preferring to
 * tell that the calling code should not warn the user.
 *
 * @author Kirill Likhodedov
 */
public class BzrCrlfProblemsDetector {

  private static final Logger LOG = Logger.getInstance(BzrCrlfProblemsDetector.class);
  private static final String CRLF = "\r\n";

  @NotNull private final Project myProject;
  @NotNull private final BzrPlatformFacade myPlatformFacade;
  @NotNull private final Bzr myBzr;

  private final boolean myShouldWarn;

  @NotNull
  public static BzrCrlfProblemsDetector detect(@NotNull Project project, @NotNull BzrPlatformFacade platformFacade,
                                               @NotNull Bzr bzr, @NotNull Collection<VirtualFile> files) {
    return new BzrCrlfProblemsDetector(project, platformFacade, bzr, files);
  }

  private BzrCrlfProblemsDetector(@NotNull Project project,
                                  @NotNull BzrPlatformFacade platformFacade,
                                  @NotNull Bzr bzr,
                                  @NotNull Collection<VirtualFile> files) {
    myProject = project;
    myPlatformFacade = platformFacade;
    myBzr = bzr;

    Map<VirtualFile, List<VirtualFile>> filesByRoots = sortFilesByRoots(files);

    boolean shouldWarn = false;
    Collection<VirtualFile> rootsWithIncorrectAutoCrlf = getRootsWithIncorrectAutoCrlf(filesByRoots);
    if (!rootsWithIncorrectAutoCrlf.isEmpty()) {
      Map<VirtualFile, Collection<VirtualFile>> crlfFilesByRoots = findFilesWithCrlf(filesByRoots, rootsWithIncorrectAutoCrlf);
      if (!crlfFilesByRoots.isEmpty()) {
        Map<VirtualFile, Collection<VirtualFile>> crlfFilesWithoutAttrsByRoots = findFilesWithoutAttrs(crlfFilesByRoots);
        shouldWarn = !crlfFilesWithoutAttrsByRoots.isEmpty();
      }
    }
    myShouldWarn = shouldWarn;
  }

  private Map<VirtualFile, Collection<VirtualFile>> findFilesWithoutAttrs(Map<VirtualFile, Collection<VirtualFile>> filesByRoots) {
    Map<VirtualFile, Collection<VirtualFile>> filesWithoutAttrsByRoot = new HashMap<VirtualFile, Collection<VirtualFile>>();
    for (Map.Entry<VirtualFile, Collection<VirtualFile>> entry : filesByRoots.entrySet()) {
      VirtualFile root = entry.getKey();
      Collection<VirtualFile> files = entry.getValue();
      Collection<VirtualFile> filesWithoutAttrs = findFilesWithoutAttrs(root, files);
      if (!filesWithoutAttrs.isEmpty()) {
        filesWithoutAttrsByRoot.put(root, filesWithoutAttrs);
      }
    }
    return filesWithoutAttrsByRoot;
  }

  @NotNull
  private Collection<VirtualFile> findFilesWithoutAttrs(@NotNull VirtualFile root, @NotNull Collection<VirtualFile> files) {
    BzrRepository repository = myPlatformFacade.getRepositoryManager(myProject).getRepositoryForRoot(root);
    if (repository == null) {
      LOG.warn("Repository is null for " + root);
      return Collections.emptyList();
    }
    Collection<String> interestingAttributes = Arrays.asList(BzrAttribute.TEXT.getName(), BzrAttribute.CRLF.getName());
    BzrCommandResult result = myBzr.checkAttr(repository, interestingAttributes, files);
    if (!result.success()) {
      LOG.warn(String.format("Couldn't git check-attr. Attributes: %s, files: %s", interestingAttributes, files));
      return Collections.emptyList();
    }
    BzrCheckAttrParser parser = BzrCheckAttrParser.parse(result.getOutput());
    Map<String, Collection<BzrAttribute>> attributes = parser.getAttributes();
    Collection<VirtualFile> filesWithoutAttrs = new ArrayList<VirtualFile>();
    for (VirtualFile file : files) {
      ProgressIndicatorProvider.checkCanceled();
      String relativePath = FileUtil.getRelativePath(root.getPath(), file.getPath(), '/');
      Collection<BzrAttribute> attrs = attributes.get(relativePath);
      if (attrs == null || !attrs.contains(BzrAttribute.TEXT) && !attrs.contains(BzrAttribute.CRLF)) {
        filesWithoutAttrs.add(file);
      }
    }
    return filesWithoutAttrs;
  }

  @NotNull
  private Map<VirtualFile, Collection<VirtualFile>> findFilesWithCrlf(@NotNull Map<VirtualFile, List<VirtualFile>> allFilesByRoots,
                                                                      @NotNull Collection<VirtualFile> rootsWithIncorrectAutoCrlf) {
    Map<VirtualFile, Collection<VirtualFile>> filesWithCrlfByRoots = new HashMap<VirtualFile, Collection<VirtualFile>>();
    for (Map.Entry<VirtualFile, List<VirtualFile>> entry : allFilesByRoots.entrySet()) {
      VirtualFile root = entry.getKey();
      List<VirtualFile> files = entry.getValue();
      if (rootsWithIncorrectAutoCrlf.contains(root)) {
        Collection<VirtualFile> filesWithCrlf = findFilesWithCrlf(files);
        if (!filesWithCrlf.isEmpty()) {
          filesWithCrlfByRoots.put(root, filesWithCrlf);
        }
      }
    }
    return filesWithCrlfByRoots;
  }

  @NotNull
  private Collection<VirtualFile> findFilesWithCrlf(@NotNull Collection<VirtualFile> files) {
    Collection<VirtualFile> filesWithCrlf = new ArrayList<VirtualFile>();
    for (VirtualFile file : files) {
      ProgressIndicatorProvider.checkCanceled();
      String separator = myPlatformFacade.getLineSeparator(file, true);
      if (CRLF.equals(separator)) {
        filesWithCrlf.add(file);
      }
    }
    return filesWithCrlf;
  }

  @NotNull
  private Collection<VirtualFile> getRootsWithIncorrectAutoCrlf(@NotNull Map<VirtualFile, List<VirtualFile>> filesByRoots) {
    Collection<VirtualFile> rootsWithIncorrectAutoCrlf = new ArrayList<VirtualFile>();
    for (Map.Entry<VirtualFile, List<VirtualFile>> entry : filesByRoots.entrySet()) {
      VirtualFile root = entry.getKey();
      boolean autocrlf = isAutoCrlfSetRight(root);
      if (!autocrlf) {
        rootsWithIncorrectAutoCrlf.add(root);
      }
    }
    return rootsWithIncorrectAutoCrlf;
  }

  private boolean isAutoCrlfSetRight(@NotNull VirtualFile root) {
    BzrRepository repository = myPlatformFacade.getRepositoryManager(myProject).getRepositoryForRoot(root);
    if (repository == null) {
      LOG.warn("Repository is null for " + root);
      return true;
    }
    BzrCommandResult result = myBzr.config(repository, BzrConfigUtil.CORE_AUTOCRLF);
    String value = result.getOutputAsJoinedString();
    return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("input");
  }

  @NotNull
  private static Map<VirtualFile, List<VirtualFile>> sortFilesByRoots(@NotNull Collection<VirtualFile> files) {
    return BzrUtil.sortFilesByBzrRootsIgnoringOthers(files);
  }

  public boolean shouldWarn() {
    return myShouldWarn;
  }

}
