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
package bazaar4idea.annotate;

import bazaar4idea.BzrFileRevision;
import bazaar4idea.BzrRevisionNumber;
import bazaar4idea.BzrUtil;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.history.BzrHistoryUtils;
import bazaar4idea.i18n.BzrBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FilePathImpl;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.annotate.*;
import com.intellij.openapi.vcs.history.VcsAbstractHistorySession;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.util.StringScanner;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bazar annotation provider implementation.
 * <p/>
 * Based on the JetBrains SVNAnnotationProvider.
 */
public class BzrAnnotationProvider implements AnnotationProvider, VcsCacheableAnnotationProvider {
  /**
   * the context project
   */
  private final Project myProject;
  /**
   * The author key for annotations
   */
  @NonNls private static final String AUTHOR_KEY = "author";
  /**
   * The committer time key for annotations
   */
  @NonNls private static final String COMMITTER_TIME_KEY = "committer-time";
  private static final Logger LOG = Logger.getInstance(BzrAnnotationProvider.class);

  /**
   * A constructor
   *
   * @param project a context project
   */
  public BzrAnnotationProvider(@NotNull Project project) {
    myProject = project;
  }

  /**
   * {@inheritDoc}
   */
  public FileAnnotation annotate(@NotNull VirtualFile file) throws VcsException {
    return annotate(file, null);
  }

  /**
   * {@inheritDoc}
   */
  public FileAnnotation annotate(@NotNull final VirtualFile file, final VcsFileRevision revision) throws VcsException {
    if (file.isDirectory()) {
      throw new VcsException("Cannot annotate a directory");
    }
    final FileAnnotation[] annotation = new FileAnnotation[1];
    final Exception[] exception = new Exception[1];
    Runnable command = new Runnable() {
      public void run() {
        final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
        try {
          final FilePath currentFilePath = VcsUtil.getFilePath(file.getPath());
          final FilePath realFilePath;
          if (progress != null) {
            progress.setText(BzrBundle.message("getting.history", file.getName()));
          }
          final List<VcsFileRevision> revisions = BzrHistoryUtils.history(myProject, currentFilePath);
          if (revision == null) {
            realFilePath = BzrHistoryUtils.getLastCommitName(myProject, currentFilePath);
          }
          else {
            realFilePath = ((BzrFileRevision)revision).getPath();
          }
          if (progress != null) {
            progress.setText(BzrBundle.message("computing.annotation", file.getName()));
          }
          final BzrFileAnnotation result = annotate(realFilePath, revision, revisions, file);
          annotation[0] = result;
        }
        catch (Exception e) {
          exception[0] = e;
        }
      }
    };
    if (ApplicationManager.getApplication().isDispatchThread()) {
      ProgressManager.getInstance()
        .runProcessWithProgressSynchronously(command, BzrBundle.getString("annotate.action.name"), false, myProject);
    }
    else {
      command.run();
    }
    if (exception[0] != null) {
      LOG.warn(exception[0]);
      throw new VcsException("Failed to annotate: " + exception[0], exception[0]);
    }
    return annotation[0];
  }

  /**
   * Calculate annotations
   *
   * @param repositoryFilePath the file path in the repository
   * @param revision           the revision to checkout
   * @param revisions          the revision list from history
   * @param file               a virtual file for the action
   * @return a file annotation object
   * @throws VcsException if there is a problem with running git
   */
  private BzrFileAnnotation annotate(final FilePath repositoryFilePath,
                                     final VcsFileRevision revision,
                                     final List<VcsFileRevision> revisions,
                                     final VirtualFile file) throws VcsException {
    BzrSimpleHandler h = new BzrSimpleHandler(myProject, BzrUtil.getBzrRoot(repositoryFilePath), BzrCommand.BLAME);
    h.setStdoutSuppressed(true);
    h.setCharset(file.getCharset());
    h.addParameters("-p", "-l", "-t", "-w");
    if (revision == null) {
      h.addParameters("HEAD");
    }
    else {
      h.addParameters(revision.getRevisionNumber().asString());
    }
    h.addRelativePaths(repositoryFilePath);
    String output = h.run();
    BzrFileAnnotation
      annotation = new BzrFileAnnotation(myProject, file, revision == null, revision == null ? null : revision.getRevisionNumber());
    class CommitInfo {
      Date date;
      String author;
      BzrRevisionNumber revision;
    }
    HashMap<String, CommitInfo> commits = new HashMap<String, CommitInfo>();
    for (StringScanner s = new StringScanner(output); s.hasMoreData();) {
      // parse header line
      String commitHash = s.spaceToken();
      if (commitHash.equals(BzrRevisionNumber.NOT_COMMITTED_REVNO)) {
        commitHash = null;
      }
      s.spaceToken(); // skip revision line number
      String s1 = s.spaceToken();
      int lineNum = Integer.parseInt(s1);
      s.nextLine();
      // parse commit information
      CommitInfo commit = commits.get(commitHash);
      if (commit != null) {
        while (s.hasMoreData() && !s.startsWith('\t')) {
          s.nextLine();
        }
      }
      else {
        commit = new CommitInfo();
        while (s.hasMoreData() && !s.startsWith('\t')) {
          String key = s.spaceToken();
          String value = s.line();
          if (commitHash != null && AUTHOR_KEY.equals(key)) {
            commit.author = value;
          }
          if (commitHash != null && COMMITTER_TIME_KEY.equals(key)) {
            commit.date = BzrUtil.parseTimestampWithNFEReport(value, h, output);
            commit.revision = new BzrRevisionNumber(commitHash, commit.date);
          }
        }
        commits.put(commitHash, commit);
      }
      // parse line
      if (!s.hasMoreData()) {
        // if the file is empty, the next line will not start with tab and it will be
        // empty.  
        continue;
      }
      s.skipChars(1);
      String line = s.line(true);
      annotation.appendLineInfo(commit.date, commit.revision, commit.author, line, lineNum);
    }
    annotation.addLogEntries(revisions);
    return annotation;
  }

  @Override
  public VcsAnnotation createCacheable(FileAnnotation fileAnnotation) {
    final BzrFileAnnotation bzrFileAnnotation = (BzrFileAnnotation) fileAnnotation;
    final int size = bzrFileAnnotation.getNumLines();
    final VcsUsualLineAnnotationData basicData = new VcsUsualLineAnnotationData(size);
    for (int i = 0; i < size; i++) {
      basicData.put(i,  bzrFileAnnotation.getLineRevisionNumber(i));
    }
    return new VcsAnnotation(new FilePathImpl(bzrFileAnnotation.getFile()), basicData, null);
  }

  @Override
  public FileAnnotation restore(VcsAnnotation vcsAnnotation,
                                VcsAbstractHistorySession session,
                                String annotatedContent,
                                boolean forCurrentRevision, VcsRevisionNumber revisionNumber) {
    final BzrFileAnnotation bzrFileAnnotation =
      new BzrFileAnnotation(myProject, vcsAnnotation.getFilePath().getVirtualFile(), forCurrentRevision, revisionNumber);
    bzrFileAnnotation.addLogEntries(session.getRevisionList());
    final VcsLineAnnotationData basicAnnotation = vcsAnnotation.getBasicAnnotation();
    final int size = basicAnnotation.getNumLines();
    final Map<VcsRevisionNumber,VcsFileRevision> historyAsMap = session.getHistoryAsMap();
    final List<String> lines = StringUtil.split(StringUtil.convertLineSeparators(annotatedContent), "\n", false, false);
    for (int i = 0; i < size; i++) {
      final VcsRevisionNumber revision = basicAnnotation.getRevision(i);
      final VcsFileRevision vcsFileRevision = historyAsMap.get(revision);
      if (vcsFileRevision == null) {
        return null;
      }
      try {
        bzrFileAnnotation.appendLineInfo(vcsFileRevision.getRevisionDate(), (BzrRevisionNumber) revision, vcsFileRevision.getAuthor(),
                                         lines.get(i), i + 1);
      }
      catch (VcsException e) {
        return null;
      }
    }
    return bzrFileAnnotation;
  }

  /**
   * {@inheritDoc}
   */
  public boolean isAnnotationValid(VcsFileRevision rev) {
    return true;
  }
}
