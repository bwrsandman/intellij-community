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
package bazaar4idea.rebase;

import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.repo.BzrRepositoryFiles;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * The utilities related to rebase functionality
 */
public class BzrRebaseUtils {
  /**
   * The logger instance
   */
  private final static Logger LOG = Logger.getInstance(BzrRebaseUtils.class.getName());

  /**
   * A private constructor for utility class
   */
  private BzrRebaseUtils() {
  }

  /**
   * Checks if the rebase is in the progress for the specified git root
   *
   * @param root the git root
   * @return true if the rebase directory presents in the root
   */
  @Deprecated
  public static boolean isRebaseInTheProgress(VirtualFile root) {
    return getRebaseDir(root) != null;
  }

  /**
   * Get rebase directory
   *
   * @param root the vcs root
   * @return the rebase directory or null if it does not exist.
   */
  @Nullable
  private static File getRebaseDir(VirtualFile root) {
    File gitDir = new File(VfsUtilCore.virtualToIoFile(root), BzrUtil.DOT_BZR);
    File f = new File(gitDir, BzrRepositoryFiles.REBASE_APPLY);
    if (f.exists()) {
      return f;
    }
    f = new File(gitDir, BzrRepositoryFiles.REBASE_MERGE);
    if (f.exists()) {
      return f;
    }
    return null;
  }

  /**
   * Get rebase directory
   *
   * @param root the vcs root
   * @return the commit information or null if no commit information could be detected
   */
  @Nullable
  public static CommitInfo getCurrentRebaseCommit(VirtualFile root) {
    File rebaseDir = getRebaseDir(root);
    if (rebaseDir == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("No rebase dir found for " + root.getPath());
      }
      return null;
    }
    File nextFile = new File(rebaseDir, "next");
    int next;
    try {
      next = Integer.parseInt(FileUtil.loadFile(nextFile, BzrUtil.UTF8_ENCODING).trim());
    }
    catch (Exception e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Failed to load next commit number from file " + nextFile.getPath(), e);
      }
      return null;
    }
    File commitFile = new File(rebaseDir, String.format("%04d", next));
    String hash = null;
    String subject = null;
    try {
      BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(commitFile), BzrUtil.UTF8_CHARSET));
      try {
        String line;
        while ((line = in.readLine()) != null) {
          if (line.startsWith("From ")) {
            hash = line.substring(5, 5 + 40);
          }
          if (line.startsWith("Subject: ")) {
            subject = line.substring("Subject: ".length());
          }
          if (hash != null && subject != null) {
            break;
          }
        }
      }
      finally {
        in.close();
      }
    }
    catch (Exception e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Failed to load next commit number from file " + commitFile, e);
      }
      return null;
    }
    if (subject == null || hash == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Unable to extract information from " + commitFile + " " + hash + ": " + subject);
      }
      return null;
    }
    return new CommitInfo(new BzrRevisionNumber(hash), subject);
  }

  /**
   * Short commit info
   */
  public static class CommitInfo {
    /**
     * The commit hash
     */
    public final BzrRevisionNumber revision;
    /**
     * The commit subject
     */
    public final String subject;

    /**
     * The constructor
     *
     * @param revision
     * @param subject the commit subject
     */
    public CommitInfo(BzrRevisionNumber revision, String subject) {
      this.revision = revision;
      this.subject = subject;
    }

    @Override
    public String toString() {
      return revision.toString();
    }
  }
}
