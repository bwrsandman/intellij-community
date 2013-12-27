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
package bazaar4idea.commands;

import bazaar4idea.i18n.BzrBundle;
import bazaar4idea.util.BzrUIUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.EventQueue;
import java.util.Collection;

/**
 * Handler utilities that allow running handlers with progress indicators
 */
public class BzrHandlerUtil {
  /**
   * The logger instance
   */
  private static final Logger LOG = Logger.getInstance(BzrHandlerUtil.class.getName());

  /**
   * a private constructor for utility class
   */
  private BzrHandlerUtil() {
  }

  /**
   * Execute simple process synchronously with progress
   *
   * @param handler        a handler
   * @param operationTitle an operation title shown in progress dialog
   * @param operationName  an operation name shown in failure dialog
   * @return A stdout content or null if there was error (exit code != 0 or exception during start).
   */
  @Nullable
  public static String doSynchronously(final BzrSimpleHandler handler, String operationTitle, @NonNls final String operationName) {
    handler.addListener(new BzrHandlerListenerBase(handler, operationName) {
      protected String getErrorText() {
        String text = handler.getStderr();
        if (text.length() == 0) {
          text = handler.getStdout();
        }
        return text;
      }
    });
    runHandlerSynchronously(handler, operationTitle, ProgressManager.getInstance(), true);
    if (!handler.isStarted() || handler.getExitCode() != 0) {
      return null;
    }
    return handler.getStdout();
  }

  /**
   * Execute simple process synchronously with progress
   *
   * @param handler        a handler
   * @param operationTitle an operation title shown in progress dialog
   * @param operationName  an operation name shown in failure dialog
   * @return An exit code
   */
  public static int doSynchronously(final BzrLineHandler handler, String operationTitle, @NonNls final String operationName) {
    return doSynchronously(handler, operationTitle, operationName, true);
  }

  /**
   * Execute simple process synchronously with progress
   *
   * @param handler        a handler
   * @param operationTitle an operation title shown in progress dialog
   * @param operationName  an operation name shown in failure dialog
   * @param showErrors     if true, the errors are shown when process is terminated
   * @return An exit code
   */
  public static int doSynchronously(final BzrLineHandler handler,
                                    String operationTitle,
                                    @NonNls final String operationName,
                                    boolean showErrors) {
    return doSynchronously(handler, operationTitle, operationName, showErrors, true);
  }


  /**
   * Execute simple process synchronously with progress
   *
   * @param handler              a handler
   * @param operationTitle       an operation title shown in progress dialog
   * @param operationName        an operation name shown in failure dialog
   * @param showErrors           if true, the errors are shown when process is terminated
   * @param setIndeterminateFlag a flag indicating that progress should be configured as indeterminate
   * @return An exit code
   */
  public static int doSynchronously(final BzrLineHandler handler,
                                    final String operationTitle,
                                    @NonNls final String operationName,
                                    final boolean showErrors,
                                    final boolean setIndeterminateFlag) {
    final ProgressManager manager = ProgressManager.getInstance();
    manager.run(new Task.Modal(handler.project(), operationTitle, false) {
      public void run(@NotNull final ProgressIndicator indicator) {
        handler.addLineListener(new BzrLineHandlerListenerProgress(indicator, handler, operationName, showErrors));
        runInCurrentThread(handler, indicator, setIndeterminateFlag, operationTitle);
      }
    });
    if (!handler.isStarted()) {
      return -1;
    }
    return handler.getExitCode();
  }


  /**
   * Run handler synchronously. The method assumes that all listeners are set up.
   *
   * @param handler              a handler to run
   * @param operationTitle       operation title
   * @param manager              a progress manager
   * @param setIndeterminateFlag if true handler is configured as indeterminate
   */
  private static void runHandlerSynchronously(final BzrHandler handler,
                                              final String operationTitle,
                                              final ProgressManager manager,
                                              final boolean setIndeterminateFlag) {
    manager.runProcessWithProgressSynchronously(new Runnable() {
      public void run() {
        runInCurrentThread(handler, manager.getProgressIndicator(), setIndeterminateFlag,
                           operationTitle);
      }
    }, operationTitle, false, handler.project());
  }

  /**
   * Run handler in the current thread
   *
   * @param handler              a handler to run
   * @param indicator            a progress manager
   * @param setIndeterminateFlag if true handler is configured as indeterminate
   * @param operationName
   */
  public static void runInCurrentThread(final BzrHandler handler,
                                        final ProgressIndicator indicator,
                                        final boolean setIndeterminateFlag,
                                        @Nullable final String operationName) {
    runInCurrentThread(handler, new Runnable() {
      public void run() {
        if (indicator != null) {
          indicator.setText(operationName == null ? BzrBundle.message("bzr.running", handler.printableCommandLine()) : operationName);
          indicator.setText2("");
          if (setIndeterminateFlag) {
            indicator.setIndeterminate(true);
          }
        }
      }
    });
  }

  /**
   * Run handler in the current thread
   *
   * @param handler         a handler to run
   * @param postStartAction an action that is executed
   */
  public static void runInCurrentThread(final BzrHandler handler, @Nullable final Runnable postStartAction) {
    handler.runInCurrentThread(postStartAction);
  }

  /**
   * Run synchronously using progress indicator, but collect exceptions instead of showing error dialog
   *
   * @param handler a handler to use
   * @return the collection of exception collected during operation
   */
  public static Collection<VcsException> doSynchronouslyWithExceptions(final BzrLineHandler handler) {
    final ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
    return doSynchronouslyWithExceptions(handler, progressIndicator, null);
  }

  /**
   * Run synchronously using progress indicator, but collect exception instead of showing error dialog
   *
   * @param handler           a handler to use
   * @param progressIndicator a progress indicator
   * @param operationName
   * @return the collection of exception collected during operation
   */
  public static Collection<VcsException> doSynchronouslyWithExceptions(final BzrLineHandler handler,
                                                                       final ProgressIndicator progressIndicator,
                                                                       @Nullable String operationName) {
    handler.addLineListener(new BzrLineHandlerListenerProgress(progressIndicator, handler, operationName, false));
    runInCurrentThread(handler, progressIndicator, false, operationName);
    return handler.errors();
  }

  public static String formatOperationName(String operation, @NotNull VirtualFile root) {
    return operation + " '" + root.getName() + "'...";
  }

  /**
   * A base class for handler listener that implements error handling logic
   */
  private abstract static class BzrHandlerListenerBase implements BzrHandlerListener {
    /**
     * a handler
     */
    protected final BzrHandler myHandler;
    /**
     * a operation name for the handler
     */
    protected final String myOperationName;
    /**
     * if true, the errors are shown when process is terminated
     */
    protected boolean myShowErrors;

    /**
     * A constructor
     *
     * @param handler       a handler instance
     * @param operationName an operation name
     */
    public BzrHandlerListenerBase(final BzrHandler handler, final String operationName) {
      this(handler, operationName, true);
    }

    /**
     * A constructor
     *
     * @param handler       a handler instance
     * @param operationName an operation name
     * @param showErrors    if true, the errors are shown when process is terminated
     */
    public BzrHandlerListenerBase(final BzrHandler handler, final String operationName, boolean showErrors) {
      myHandler = handler;
      myOperationName = operationName;
      myShowErrors = showErrors;
    }

    /**
     * {@inheritDoc}
     */
    public void processTerminated(final int exitCode) {
      if (exitCode != 0 && !myHandler.isIgnoredErrorCode(exitCode)) {
        ensureError(exitCode);
        if (myShowErrors) {
          EventQueue.invokeLater(new Runnable() {
            public void run() {
              BzrUIUtil.showOperationErrors(myHandler.project(), myHandler.errors(), myOperationName);
            }
          });
        }
      }
    }

    /**
     * Ensure that at least one error is available in case if the process exited with non-zero exit code
     *
     * @param exitCode the exit code of the process
     */
    protected void ensureError(final int exitCode) {
      if (myHandler.errors().isEmpty()) {
        String text = getErrorText();
        if ((text == null || text.length() == 0) && myHandler.errors().isEmpty()) {
          //noinspection ThrowableInstanceNeverThrown
          myHandler.addError(new VcsException(BzrBundle.message("bzr.error.exit", exitCode)));
        }
        else {
          //noinspection ThrowableInstanceNeverThrown
          myHandler.addError(new VcsException(text));
        }
      }
    }

    /**
     * @return error text for the handler, if null or empty string a default message is used.
     */
    protected abstract String getErrorText();

    /**
     * {@inheritDoc}
     */
    public void startFailed(final Throwable exception) {
      //noinspection ThrowableInstanceNeverThrown
      myHandler.addError(new VcsException("Bazaar start failed: " + exception.getMessage(), exception));
      if (myShowErrors) {
        EventQueue.invokeLater(new Runnable() {
          public void run() {
            BzrUIUtil.showOperationError(myHandler.project(), myOperationName, exception.getMessage());
          }
        });
      }
    }
  }

  /**
   * A base class for line handler listeners
   */
  private abstract static class BzrLineHandlerListenerBase extends BzrHandlerListenerBase implements BzrLineHandlerListener {
    /**
     * A constructor
     *
     * @param handler       a handler instance
     * @param operationName an operation name
     * @param showErrors    if true, the errors are shown when process is terminated
     */
    public BzrLineHandlerListenerBase(BzrHandler handler, String operationName, boolean showErrors) {
      super(handler, operationName, showErrors);
    }

  }

  /**
   * A base class for line handler listeners
   */
  public static class BzrLineHandlerListenerProgress extends BzrLineHandlerListenerBase {
    /**
     * a progress manager to use
     */
    private final ProgressIndicator myProgressIndicator;

    /**
     * A constructor
     *
     * @param manager       the project manager
     * @param handler       a handler instance
     * @param operationName an operation name
     * @param showErrors    if true, the errors are shown when process is terminated
     */
    public BzrLineHandlerListenerProgress(final ProgressIndicator manager, BzrHandler handler, String operationName, boolean showErrors) {
      super(handler, operationName, showErrors);    //To change body of overridden methods use File | Settings | File Templates.
      myProgressIndicator = manager;
    }

    /**
     * {@inheritDoc}
     */
    protected String getErrorText() {
      // all lines are already calculated as errors
      return "";
    }

    /**
     * {@inheritDoc}
     */
    public void onLineAvailable(final String line, final Key outputType) {
      if (isErrorLine(line.trim())) {
        //noinspection ThrowableInstanceNeverThrown
        myHandler.addError(new VcsException(line));
      }
      if (myProgressIndicator != null) {
        myProgressIndicator.setText2(line);
      }
    }
  }

  /**
   * Check if the line is an error line
   *
   * @param text a line to check
   * @return true if the error line
   */
  protected static boolean isErrorLine(String text) {
    for (String prefix : BzrImpl.ERROR_INDICATORS) {
      if (text.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }


}
