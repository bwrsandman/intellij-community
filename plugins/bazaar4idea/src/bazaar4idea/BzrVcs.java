/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package bazaar4idea;

import bazaar4idea.annotate.BzrAnnotationProvider;
import bazaar4idea.annotate.BzrRepositoryForAnnotationsListener;
import bazaar4idea.changes.BzrCommittedChangeListProvider;
import bazaar4idea.changes.BzrOutgoingChangesProvider;
import bazaar4idea.checkin.BzrCheckinEnvironment;
import bazaar4idea.checkin.BzrCommitAndPushExecutor;
import bazaar4idea.checkout.BzrCheckoutProvider;
import bazaar4idea.commands.Bzr;
import bazaar4idea.diff.BzrDiffProvider;
import bazaar4idea.diff.BzrTreeDiffProvider;
import bazaar4idea.history.BzrHistoryProvider;
import bazaar4idea.history.NewBzrUsersComponent;
import bazaar4idea.history.browser.BzrHeavyCommit;
import bazaar4idea.history.browser.BzrProjectLogManager;
import bazaar4idea.history.wholeTree.BzrCommitDetailsProvider;
import bazaar4idea.history.wholeTree.BzrCommitsSequentialIndex;
import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.merge.BzrMergeProvider;
import bazaar4idea.rollback.BzrRollbackEnvironment;
import bazaar4idea.roots.BzrIntegrationEnabler;
import bazaar4idea.status.BzrChangeProvider;
import bazaar4idea.ui.branch.BzrBranchWidget;
import bazaar4idea.update.BzrUpdateEnvironment;
import bazaar4idea.vfs.BzrVFSListener;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.*;
import com.intellij.notification.impl.NotificationsConfigurationImpl;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diff.impl.patch.formove.FilePathComparator;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.changes.CommitExecutor;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.diff.RevisionSelector;
import com.intellij.openapi.vcs.history.VcsHistoryProvider;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.merge.MergeProvider;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vcs.roots.VcsRootDetectInfo;
import com.intellij.openapi.vcs.roots.VcsRootDetector;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.vcs.update.UpdateEnvironment;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ComparatorDelegate;
import com.intellij.util.containers.Convertor;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcs.log.VcsLog;
import bazaar4idea.config.*;
import bazaar4idea.history.wholeTree.BzrCommitsSequentially;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bazaar VCS implementation
 */
public class BzrVcs extends AbstractVcs<CommittedChangeList> {
  public static final NotificationGroup NOTIFICATION_GROUP_ID = NotificationGroup.toolWindowGroup(
    "Bazaar Messages", ChangesViewContentManager.TOOLWINDOW_ID, true);
  public static final NotificationGroup IMPORTANT_ERROR_NOTIFICATION = new NotificationGroup(
    "Bazaar Important Messages", NotificationDisplayType.STICKY_BALLOON, true);
  public static final NotificationGroup MINOR_NOTIFICATION = new NotificationGroup(
    "Bazaar Minor Notifications", NotificationDisplayType.BALLOON, true);

  static {
    NotificationsConfigurationImpl.remove("Bazaar");
  }

  public static final String NAME = "Bazaar";

  /**
   * Provide selected Bazaar commit in some commit list. Use this, when {@link Change} is not enough.
   * @see VcsDataKeys#CHANGES
   * @deprecated Use {@link VcsLog#getSelectedCommits()}
   */
  @Deprecated
  public static final DataKey<BzrHeavyCommit> BZR_COMMIT = DataKey.create("Bazaar.Commit");

  /**
   * Provides the list of Bazaar commits selected in some list, for example, in the Bazaar log.
   * @deprecated Use {@link VcsLog#getSelectedCommits()}
   */
  @Deprecated
  public static final DataKey<List<BzrHeavyCommit>> SELECTED_COMMITS = DataKey.create("Bazaar.Selected.Commits");

  /**
   * Provides the possibility to receive on demand those commit details which usually are not accessible from the {@link bazaar4idea.history.browser.BzrHeavyCommit} object.
   * @deprecated Use {@link VcsLog#getSelectedCommits()}
   */
  @Deprecated
  public static final DataKey<BzrCommitDetailsProvider> COMMIT_DETAILS_PROVIDER = DataKey.create("Bazaar.Commits.Details.Provider");

  private static final Logger log = Logger.getInstance(BzrVcs.class.getName());
  private static final VcsKey ourKey = createKey(NAME);

  private final ChangeProvider myChangeProvider;
  private final BzrCheckinEnvironment myCheckinEnvironment;
  private final RollbackEnvironment myRollbackEnvironment;
  private final BzrUpdateEnvironment myUpdateEnvironment;
  private final BzrAnnotationProvider myAnnotationProvider;
  private final DiffProvider myDiffProvider;
  private final VcsHistoryProvider myHistoryProvider;
  @NotNull private final Bzr myBzr;
  private final ProjectLevelVcsManager myVcsManager;
  private final BzrVcsApplicationSettings myAppSettings;
  private final Configurable myConfigurable;
  private final RevisionSelector myRevSelector;
  private final BzrCommittedChangeListProvider myCommittedChangeListProvider;
  private final @NotNull BzrPlatformFacade myPlatformFacade;

  private BzrVFSListener myVFSListener; // a VFS listener that tracks file addition, deletion, and renaming.

  private final ReadWriteLock myCommandLock = new ReentrantReadWriteLock(true); // The command read/write lock
  private final TreeDiffProvider myTreeDiffProvider;
  private final BzrCommitAndPushExecutor myCommitAndPushExecutor;
  private final BzrExecutableValidator myExecutableValidator;
  private BzrBranchWidget myBranchWidget;

  private BzrVersion myVersion = BzrVersion.NULL; // version of Bazaar which this plugin uses.
  private static final int MAX_CONSOLE_OUTPUT_SIZE = 10000;
  private BzrRepositoryForAnnotationsListener myRepositoryForAnnotationsListener;

  @Nullable
  public static BzrVcs getInstance(Project project) {
    if (project == null || project.isDisposed()) {
      return null;
    }
    return (BzrVcs) ProjectLevelVcsManager.getInstance(project).findVcsByName(NAME);
  }

  public BzrVcs(@NotNull Project project,
                @NotNull Bzr bzr,
                @NotNull final ProjectLevelVcsManager gitVcsManager,
                @NotNull final BzrAnnotationProvider bzrAnnotationProvider,
                @NotNull final BzrDiffProvider bzrDiffProvider,
                @NotNull final BzrHistoryProvider bzrHistoryProvider,
                @NotNull final BzrRollbackEnvironment bzrRollbackEnvironment,
                @NotNull final BzrVcsApplicationSettings gitSettings,
                @NotNull final BzrVcsSettings gitProjectSettings) {
    super(project, NAME);
    myBzr = bzr;
    myVcsManager = gitVcsManager;
    myAppSettings = gitSettings;
    myChangeProvider = project.isDefault() ? null : ServiceManager.getService(project, BzrChangeProvider.class);
    myCheckinEnvironment = project.isDefault() ? null : ServiceManager.getService(project, BzrCheckinEnvironment.class);
    myAnnotationProvider = bzrAnnotationProvider;
    myDiffProvider = bzrDiffProvider;
    myHistoryProvider = bzrHistoryProvider;
    myRollbackEnvironment = bzrRollbackEnvironment;
    myRevSelector = new BzrRevisionSelector();
    myConfigurable = new BzrVcsConfigurable(gitProjectSettings, myProject);
    myUpdateEnvironment = new BzrUpdateEnvironment(myProject, this, gitProjectSettings);
    myCommittedChangeListProvider = new BzrCommittedChangeListProvider(myProject);
    myOutgoingChangesProvider = new BzrOutgoingChangesProvider(myProject);
    myTreeDiffProvider = new BzrTreeDiffProvider(myProject);
    myCommitAndPushExecutor = new BzrCommitAndPushExecutor(myCheckinEnvironment);
    myExecutableValidator = new BzrExecutableValidator(myProject, this);
    myPlatformFacade = ServiceManager.getService(myProject, BzrPlatformFacade.class);
  }


  public ReadWriteLock getCommandLock() {
    return myCommandLock;
  }

  /**
   * Run task in background using the common queue (per project)
   * @param task the task to run
   */
  public static void runInBackground(Task.Backgroundable task) {
    task.queue();
  }

  @Override
  public CommittedChangesProvider getCommittedChangesProvider() {
    return myCommittedChangeListProvider;
  }

  @Override
  public String getRevisionPattern() {
    // return the full commit hash pattern, possibly other revision formats should be supported as well
    return "[0-9a-fA-F]+";
  }

  @Override
  @NotNull
  public CheckinEnvironment createCheckinEnvironment() {
    return myCheckinEnvironment;
  }

  @NotNull
  @Override
  public MergeProvider getMergeProvider() {
    return BzrMergeProvider.detect(myProject);
  }

  @Override
  @NotNull
  public RollbackEnvironment createRollbackEnvironment() {
    return myRollbackEnvironment;
  }

  @Override
  @NotNull
  public VcsHistoryProvider getVcsHistoryProvider() {
    return myHistoryProvider;
  }

  @Override
  public VcsHistoryProvider getVcsBlockHistoryProvider() {
    return myHistoryProvider;
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return NAME;
  }

  @Override
  @Nullable
  public UpdateEnvironment createUpdateEnvironment() {
    return myUpdateEnvironment;
  }

  @Override
  @NotNull
  public BzrAnnotationProvider getAnnotationProvider() {
    return myAnnotationProvider;
  }

  @Override
  @NotNull
  public DiffProvider getDiffProvider() {
    return myDiffProvider;
  }

  @Override
  @Nullable
  public RevisionSelector getRevisionSelector() {
    return myRevSelector;
  }

  @Override
  @Nullable
  public VcsRevisionNumber parseRevisionNumber(@Nullable String revision, @Nullable FilePath path) throws VcsException {
    if (revision == null || revision.length() == 0) return null;
    if (revision.length() > 40) {    // date & revision-id encoded string
      String dateString = revision.substring(0, revision.indexOf("["));
      String rev = revision.substring(revision.indexOf("[") + 1, 40);
      Date d = new Date(Date.parse(dateString));
      return new BzrRevisionNumber(rev, d);
    }
    if (path != null) {
      try {
        VirtualFile root = BzrUtil.getBzrRoot(path);
        return BzrRevisionNumber.resolve(myProject, root, revision);
      }
      catch (VcsException e) {
        log.info("Unexpected problem with resolving the git revision number: ", e);
        throw e;
      }
    }
    return new BzrRevisionNumber(revision);

  }

  @Override
  @Nullable
  public VcsRevisionNumber parseRevisionNumber(@Nullable String revision) throws VcsException {
    return parseRevisionNumber(revision, null);
  }

  @Override
  public boolean isVersionedDirectory(VirtualFile dir) {
    return dir.isDirectory() && BzrUtil.bzrRootOrNull(dir) != null;
  }

  @Override
  protected void start() throws VcsException {
  }

  @Override
  protected void shutdown() throws VcsException {
  }

  @Override
  protected void activate() {
    checkExecutableAndVersion();

    if (myVFSListener == null) {
      myVFSListener = new BzrVFSListener(myProject, this, myBzr);
    }
    NewBzrUsersComponent.getInstance(myProject).activate();
    if (!Registry.is("bzr.new.log")) {
      BzrProjectLogManager.getInstance(myProject).activate();
    }

    if (!ApplicationManager.getApplication().isHeadlessEnvironment()) {
      myBranchWidget = new BzrBranchWidget(myProject);
      DvcsUtil.installStatusBarWidget(myProject, myBranchWidget);
    }
    if (myRepositoryForAnnotationsListener == null) {
      myRepositoryForAnnotationsListener = new BzrRepositoryForAnnotationsListener(myProject);
    }
    ((BzrCommitsSequentialIndex) ServiceManager.getService(BzrCommitsSequentially.class)).activate();
  }

  private void checkExecutableAndVersion() {
    boolean executableIsAlreadyCheckedAndFine = false;
    String pathToBzr = myAppSettings.getPathToBzr();
    if (!pathToBzr.contains(File.separator)) { // no path, just sole executable, with a hope that it is in path
      // subject to redetect the path if executable validator fails
      if (!myExecutableValidator.isExecutableValid()) {
        myAppSettings.setPathToBzr(new BzrExecutableDetector().detect());
      }
      else {
        executableIsAlreadyCheckedAndFine = true; // not to check it twice
      }
    }

    if (executableIsAlreadyCheckedAndFine || myExecutableValidator.checkExecutableAndNotifyIfNeeded()) {
      checkVersion();
    }
  }

  @Override
  protected void deactivate() {
    if (myVFSListener != null) {
      Disposer.dispose(myVFSListener);
      myVFSListener = null;
    }
    NewBzrUsersComponent.getInstance(myProject).deactivate();
    BzrProjectLogManager.getInstance(myProject).deactivate();

    if (myBranchWidget != null) {
      DvcsUtil.removeStatusBarWidget(myProject, myBranchWidget);
      myBranchWidget = null;
    }
    ((BzrCommitsSequentialIndex) ServiceManager.getService(BzrCommitsSequentially.class)).deactivate();
  }

  @NotNull
  @Override
  public synchronized Configurable getConfigurable() {
    return myConfigurable;
  }

  @Nullable
  public ChangeProvider getChangeProvider() {
    return myChangeProvider;
  }

  /**
   * Show errors as popup and as messages in vcs view.
   *
   * @param list   a list of errors
   * @param action an action
   */
  public void showErrors(@NotNull List<VcsException> list, @NotNull String action) {
    if (list.size() > 0) {
      StringBuilder buffer = new StringBuilder();
      buffer.append("\n");
      buffer.append(BzrBundle.message("error.list.title", action));
      for (final VcsException exception : list) {
        buffer.append("\n");
        buffer.append(exception.getMessage());
      }
      final String msg = buffer.toString();
      UIUtil.invokeLaterIfNeeded(new Runnable() {
        @Override
        public void run() {
          Messages.showErrorDialog(myProject, msg, BzrBundle.getString("error.dialog.title"));
        }
      });
    }
  }

  /**
   * Shows a plain message in the Version Control Console.
   */
  public void showMessages(@NotNull String message) {
    if (message.length() == 0) return;
    showMessage(message, ConsoleViewContentType.NORMAL_OUTPUT.getAttributes());
  }

  /**
   * Show message in the Version Control Console
   * @param message a message to show
   * @param style   a style to use
   */
  private void showMessage(@NotNull String message, final TextAttributes style) {
    if (message.length() > MAX_CONSOLE_OUTPUT_SIZE) {
      message = message.substring(0, MAX_CONSOLE_OUTPUT_SIZE);
    }
    myVcsManager.addMessageToConsoleWindow(message, style);
  }

  /**
   * Checks Bazaar version and updates the myVersion variable.
   * In the case of exception or unsupported version reports the problem.
   * Note that unsupported version is also applied - some functionality might not work (we warn about that), but no need to disable at all.
   */
  public void checkVersion() {
    final String executable = myAppSettings.getPathToBzr();
    try {
      myVersion = BzrVersion.identifyVersion(executable);
      if (! myVersion.isSupported()) {
        log.info("Unsupported Bazaar version: " + myVersion);
        final String SETTINGS_LINK = "settings";
        final String UPDATE_LINK = "update";
        String message = String.format("The <a href='" + SETTINGS_LINK + "'>configured</a> version of Bazaar is not supported: %s.<br/> " +
                                       "The minimal supported version is %%s. Please <a href='" + UPDATE_LINK + "'>update</a>.",
                                       myVersion);
        IMPORTANT_ERROR_NOTIFICATION.createNotification("Unsupported Bazaar version", message, NotificationType.ERROR,
          new NotificationListener.Adapter() {
            @Override
            protected void hyperlinkActivated(@NotNull Notification notification, @NotNull HyperlinkEvent e) {
              if (SETTINGS_LINK.equals(e.getDescription())) {
                ShowSettingsUtil.getInstance().showSettingsDialog(myProject, getConfigurable().getDisplayName());
              }
              else if (UPDATE_LINK.equals(e.getDescription())) {
                BrowserUtil.browse("http://bazaar.canonical.com");
              }
            }
        }).notify(myProject);
      }
    } catch (Exception e) {
      if (getExecutableValidator().checkExecutableAndNotifyIfNeeded()) { // check executable before notifying error
        final String reason = (e.getCause() != null ? e.getCause() : e).getMessage();
        String message = BzrBundle.message("vcs.unable.to.run.bzr", executable, reason);
        if (!myProject.isDefault()) {
          showMessage(message, ConsoleViewContentType.SYSTEM_OUTPUT.getAttributes());
        }
        VcsBalloonProblemNotifier.showOverVersionControlView(myProject, message, MessageType.ERROR);
      }
    }
  }

  /**
   * @return the version number of Bazaar, which is used by IDEA. Or {@link bazaar4idea.config.BzrVersion#NULL} if version info is unavailable yet.
   */
  @NotNull
  public BzrVersion getVersion() {
    return myVersion;
  }

  /**
   * Shows a command line message in the Version Control Console
   */
  public void showCommandLine(final String cmdLine) {
    SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS");
    showMessage(f.format(new Date()) + ": " + cmdLine, ConsoleViewContentType.SYSTEM_OUTPUT.getAttributes());
  }

  /**
   * Shows error message in the Version Control Console
   */
  public void showErrorMessages(final String line) {
    showMessage(line, ConsoleViewContentType.ERROR_OUTPUT.getAttributes());
  }

  @Override
  public boolean allowsNestedRoots() {
    return true;
  }

  @Override
  public <S> List<S> filterUniqueRoots(final List<S> in, final Convertor<S, VirtualFile> convertor) {
    Collections.sort(in, new ComparatorDelegate<S, VirtualFile>(convertor, FilePathComparator.getInstance()));

    for (int i = 1; i < in.size(); i++) {
      final S sChild = in.get(i);
      final VirtualFile child = convertor.convert(sChild);
      final VirtualFile childRoot = BzrUtil.bzrRootOrNull(child);
      if (childRoot == null) {
        // non-git file actually, skip it
        continue;
      }
      for (int j = i - 1; j >= 0; --j) {
        final S sParent = in.get(j);
        final VirtualFile parent = convertor.convert(sParent);
        // the method check both that parent is an ancestor of the child and that they share common git root
        if (VfsUtilCore.isAncestor(parent, child, false) && VfsUtilCore.isAncestor(childRoot, parent, false)) {
          in.remove(i);
          //noinspection AssignmentToForLoopParameter
          --i;
          break;
        }
      }
    }
    return in;
  }

  @Override
  public RootsConvertor getCustomConvertor() {
    return BzrRootConverter.INSTANCE;
  }

  public static VcsKey getKey() {
    return ourKey;
  }

  @Override
  public VcsType getType() {
    return VcsType.distributed;
  }

  private final VcsOutgoingChangesProvider<CommittedChangeList> myOutgoingChangesProvider;

  @Override
  protected VcsOutgoingChangesProvider<CommittedChangeList> getOutgoingProviderImpl() {
    return myOutgoingChangesProvider;
  }

  @Override
  public RemoteDifferenceStrategy getRemoteDifferenceStrategy() {
    return RemoteDifferenceStrategy.ASK_TREE_PROVIDER;
  }

  @Override
  protected TreeDiffProvider getTreeDiffProviderImpl() {
    return myTreeDiffProvider;
  }

  @Override
  public List<CommitExecutor> getCommitExecutors() {
    return Collections.<CommitExecutor>singletonList(myCommitAndPushExecutor);
  }

  @NotNull
  public BzrExecutableValidator getExecutableValidator() {
    return myExecutableValidator;
  }

  @Override
  public boolean fileListenerIsSynchronous() {
    return false;
  }

  @Override
  @CalledInAwt
  public void enableIntegration() {
    ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
      public void run() {
        VcsRootDetectInfo detectInfo = new VcsRootDetector(myProject).detect();
        new BzrIntegrationEnabler(myProject, myBzr, myPlatformFacade).enable(detectInfo);
      }
    });
  }

  @Override
  public CheckoutProvider getCheckoutProvider() {
    return new BzrCheckoutProvider(ServiceManager.getService(Bzr.class));
  }
}
