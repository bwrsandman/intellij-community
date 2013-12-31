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
package bazaar4idea.checkout;

import bazaar4idea.BzrVcs;
import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.commands.BzrStandardProgressAnalyzer;
import bazaar4idea.update.BzrFetchResult;
import bazaar4idea.update.BzrFetcher;
import com.intellij.dvcs.ui.DvcsBundle;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CheckoutProvider;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.Notificator;
import bazaar4idea.actions.BasicAction;
import bazaar4idea.commands.BzrLineHandlerListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checkout provider for the Bazaar
 */
public class BzrCheckoutProvider implements CheckoutProvider {

  private final Bzr myBzr;

  public BzrCheckoutProvider(@NotNull Bzr bzr) {
    myBzr = bzr;
  }

  public String getVcsName() {
    return "_Bazaar";
  }

  public void doCheckout(@NotNull final Project project, @Nullable final Listener listener) {
    BasicAction.saveAll();
    BzrCloneDialog dialog = new BzrCloneDialog(project);
    dialog.show();
    if (!dialog.isOK()) {
      return;
    }
    dialog.rememberSettings();
    final LocalFileSystem lfs = LocalFileSystem.getInstance();
    final File parent = new File(dialog.getParentDirectory());
    VirtualFile destinationParent = lfs.findFileByIoFile(parent);
    if (destinationParent == null) {
      destinationParent = lfs.refreshAndFindFileByIoFile(parent);
    }
    if (destinationParent == null) {
      return;
    }
    final String sourceRepositoryURL = dialog.getSourceRepositoryURL();
    final String directoryName = dialog.getDirectoryName();
    final String parentDirectory = dialog.getParentDirectory();
    clone(project, myBzr, listener, destinationParent, sourceRepositoryURL, directoryName, parentDirectory);
  }

  public static void clone(final Project project, @NotNull final Bzr bzr, final Listener listener, final VirtualFile destinationParent,
                    final String sourceRepositoryURL, final String directoryName, final String parentDirectory) {

    final AtomicBoolean cloneResult = new AtomicBoolean();
    new Task.Backgroundable(project, DvcsBundle.message("cloning.repository", sourceRepositoryURL)) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        cloneResult.set(doClone(project, indicator, bzr, directoryName, parentDirectory, sourceRepositoryURL));
      }

      @Override
      public void onSuccess() {
        if (!cloneResult.get()) {
          return;
        }

        destinationParent.refresh(true, true, new Runnable() {
          public void run() {
            if (project.isOpen() && (!project.isDisposed()) && (!project.isDefault())) {
              final VcsDirtyScopeManager mgr = VcsDirtyScopeManager.getInstance(project);
              mgr.fileDirty(destinationParent);
            }
          }
        });
        listener.directoryCheckedOut(new File(parentDirectory, directoryName), BzrVcs.getKey());
        listener.checkoutCompleted();
      }
    }.queue();
  }

  public static boolean doClone(@NotNull Project project, @NotNull ProgressIndicator indicator, @NotNull Bzr bzr,
                                @NotNull String directoryName, @NotNull String parentDirectory, @NotNull String sourceRepositoryURL) {

    return cloneNatively(project, indicator, bzr, new File(parentDirectory), sourceRepositoryURL, directoryName);
  }

  private static boolean cloneNatively(@NotNull Project project, @NotNull final ProgressIndicator indicator,
                                       @NotNull Bzr bzr, @NotNull File directory, @NotNull String url, @NotNull String cloneDirectoryName) {
    indicator.setIndeterminate(false);
    BzrLineHandlerListener progressListener = BzrStandardProgressAnalyzer.createListener(indicator);
    BzrCommandResult result = bzr.clone(project, directory, url, cloneDirectoryName, progressListener);
    if (result.success()) {
      return true;
    }
    Notificator.getInstance(project).notifyError("Clone failed", result.getErrorOutputAsHtmlString());
    return false;
  }

}
