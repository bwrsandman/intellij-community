/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

import bazaar4idea.BzrVcs;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Task to run the given BzrHandler with ability to cancel it.
 *
 * <b>Cancellation</b> is implemented with a {@link java.util.Timer} which checks whether the ProgressIndicator was cancelled and kills
 * the BzrHandler in that case.
 *
 * A bzrTask may be executed synchronously ({@link #executeModal()} or asynchronously ({@link #executeAsync(BzrTask.ResultHandler)}.
 * Result of the execution is encapsulated in {@link BzrTaskResult}.
 *
 * <p>
 * <b>BzrTaskResultHandler</b> is called from AWT-thread. Use {@link #setExecuteResultInAwt(boolean) setExecuteResultInAwt(false)}
 * to execute the result {@link com.intellij.openapi.application.Application#executeOnPooledThread(Runnable) on the pooled thread}.
 * </p>
 *
 * @author Kirill Likhodedov
 */
public class BzrTask {

  private static final Logger LOG = Logger.getInstance(BzrTask.class);

  private final Project myProject;
  private final BzrHandler myHandler;
  private final String myTitle;
  private BzrProgressAnalyzer myProgressAnalyzer;
  private ProgressIndicator myProgressIndicator;

  public BzrTask(Project project, BzrHandler handler, String title) {
    myProject = project;
    myHandler = handler;
    myTitle = title;
  }

  /**
   * Executes this task synchronously, with a modal progress dialog.
   * @return Result of the task execution.
   */
  public BzrTaskResult executeModal() {
    return execute(true);
  }

  /**
   * Executes the task synchronously, with a modal progress dialog.
   * @param resultHandler callback which will be called after task execution.
   */
  public void executeModal(BzrTaskResultHandler resultHandler) {
    execute(true, true, resultHandler);
  }

  /**
   * Executes this task asynchronously, in background. Calls the resultHandler when finished.
   * @param resultHandler callback called after the task has finished or was cancelled by user or automatically.
   */
  public void executeAsync(final BzrTaskResultHandler resultHandler) {
    execute(false, false, resultHandler);
  }

  public void executeInBackground(boolean sync, final BzrTaskResultHandler resultHandler) {
    execute(sync, false, resultHandler);
  }

  // this is always sync
  @NotNull
  public BzrTaskResult execute(boolean modal) {
    final AtomicReference<BzrTaskResult> result = new AtomicReference<BzrTaskResult>(BzrTaskResult.INITIAL);
    execute(true, modal, new BzrTaskResultHandlerAdapter() {
      @Override
      protected void run(BzrTaskResult res) {
        result.set(res);
      }
    });
    return result.get();
  }

  /**
   * The most general execution method.
   * @param sync  Set to <code>true</code> to make the calling thread wait for the task execution.
   * @param modal If <code>true</code>, the task will be modal with a modal progress dialog. If false, the task will be executed in
   * background. <code>modal</code> implies <code>sync</code>, i.e. if modal then sync doesn't matter: you'll wait anyway.
   * @param resultHandler Handle the result.
   * @see #execute(boolean)
   */
  public void execute(boolean sync, boolean modal, final BzrTaskResultHandler resultHandler) {
    final Object LOCK = new Object();
    final AtomicBoolean completed = new AtomicBoolean();

    if (modal) {
      final ModalTask task = new ModalTask(myProject, myHandler, myTitle) {
        @Override public void onSuccess() {
          commonOnSuccess(LOCK, resultHandler);
          completed.set(true);
        }
        @Override public void onCancel() {
          commonOnCancel(LOCK, resultHandler);
          completed.set(true);
        }
      };
      ApplicationManager.getApplication().invokeAndWait(new Runnable() {
        @Override
        public void run() {
          ProgressManager.getInstance().run(task);
        }
      }, ModalityState.defaultModalityState());
    } else {
      final BackgroundableTask task = new BackgroundableTask(myProject, myHandler, myTitle) {
        @Override public void onSuccess() {
          commonOnSuccess(LOCK, resultHandler);
          completed.set(true);
        }
        @Override public void onCancel() {
          commonOnCancel(LOCK, resultHandler);
          completed.set(true);
        }
      };
      if (myProgressIndicator == null) {
        BzrVcs.runInBackground(task);
      } else {
        task.runAlone();
      }
    }

    if (sync) {
      while (!completed.get()) {
        try {
          synchronized (LOCK) {
            LOCK.wait(50);
          }
        } catch (InterruptedException e) {
          LOG.info(e);
        }
      }
    }
  }

  private void commonOnSuccess(final Object LOCK, final BzrTaskResultHandler resultHandler) {
    BzrTaskResult res = myHandler.errors().isEmpty() ? BzrTaskResult.OK : BzrTaskResult.BAZAAR_ERROR;
    resultHandler.run(res);
    synchronized (LOCK) {
      LOCK.notifyAll();
    }
  }

  private void commonOnCancel(final Object LOCK, final BzrTaskResultHandler resultHandler) {
    resultHandler.run(BzrTaskResult.CANCELLED);
    synchronized (LOCK) {
      LOCK.notifyAll();
    }
  }

  private void addListeners(final TaskExecution task, final ProgressIndicator indicator) {
    if (indicator != null) {
      indicator.setIndeterminate(myProgressAnalyzer == null);
    }
    // When receives an error line, adds a VcsException to the BzrHandler.
    final BzrLineHandlerListener listener = new BzrLineHandlerListener() {
      @Override
      public void processTerminated(int exitCode) {
        if (exitCode != 0 && !myHandler.isIgnoredErrorCode(exitCode)) {
          if (myHandler.errors().isEmpty()) {
            myHandler.addError(new VcsException(myHandler.getLastOutput()));
          }
        }
      }

      @Override
      public void startFailed(Throwable exception) {
        myHandler.addError(new VcsException("Bazaar start failed: " + exception.getMessage(), exception));
      }

      @Override
      public void onLineAvailable(String line, Key outputType) {
        if (BzrHandlerUtil.isErrorLine(line.trim())) {
          myHandler.addError(new VcsException(line));
        } else if (!StringUtil.isEmptyOrSpaces(line)) {
          myHandler.addLastOutput(line);
        }
        if (indicator != null) {
          indicator.setText2(line);
        }
        if (myProgressAnalyzer != null && indicator != null) {
          final double fraction = myProgressAnalyzer.analyzeProgress(line);
          if (fraction >= 0) {
            indicator.setFraction(fraction);
          }
        }
      }
    };

    if (myHandler instanceof BzrLineHandler) {
      ((BzrLineHandler)myHandler).addLineListener(listener);
    } else {
      myHandler.addListener(listener);
    }

    // disposes the timer
    myHandler.addListener(new BzrHandlerListener() {
      @Override
      public void processTerminated(int exitCode) {
        task.dispose();
      }

      @Override
      public void startFailed(Throwable exception) {
        task.dispose();
      }
    });
  }

  public void setProgressAnalyzer(BzrProgressAnalyzer progressAnalyzer) {
    myProgressAnalyzer = progressAnalyzer;
  }

  public void setProgressIndicator(ProgressIndicator progressIndicator) {
    myProgressIndicator = progressIndicator;
  }

  /**
   * We're using this interface here to work with Task, because standard {@link Task#run(com.intellij.openapi.progress.ProgressIndicator)}
   * is busy with timers.
   */
  private interface TaskExecution {
    void execute(ProgressIndicator indicator);
    void dispose();
  }

  // To add to {@link com.intellij.openapi.progress.BackgroundTaskQueue} a task must be {@link Task.Backgroundable},
  // so we can't have a single class representing a task: we have BackgroundableTask and ModalTask.
  // To minimize code duplication we use BzrTaskDelegate.

  private abstract class BackgroundableTask extends Task.Backgroundable implements TaskExecution {
    private BzrTaskDelegate myDelegate;

    public BackgroundableTask(@Nullable final Project project, @NotNull BzrHandler handler, @NotNull final String processTitle) {
      super(project, processTitle, true);
      myDelegate = new BzrTaskDelegate(myProject, handler, this);
    }

    @Override
    public final void run(@NotNull ProgressIndicator indicator) {
      myDelegate.run(indicator);
    }

    public final void runAlone() {
      if (ApplicationManager.getApplication().isDispatchThread()) {
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
          @Override
          public void run() {
            justRun();
          }
        });
      } else {
        justRun();
      }
    }

    private void justRun() {
      String oldTitle = myProgressIndicator.getText();
      myProgressIndicator.setText(myTitle);
      myDelegate.run(myProgressIndicator);
      myProgressIndicator.setText(oldTitle);
      if (myProgressIndicator.isCanceled()) {
        onCancel();
      } else {
        onSuccess();
      }
    }

    @Override
    public void execute(ProgressIndicator indicator) {
      addListeners(this, indicator);
      BzrHandlerUtil.runInCurrentThread(myHandler, indicator, false, myTitle);
    }

    @Override
    public void dispose() {
      Disposer.dispose(myDelegate);
    }
  }

  private abstract class ModalTask extends Task.Modal implements TaskExecution {
    private BzrTaskDelegate myDelegate;

    public ModalTask(@Nullable final Project project, @NotNull BzrHandler handler, @NotNull final String processTitle) {
      super(project, processTitle, true);
      myDelegate = new BzrTaskDelegate(myProject, handler, this);
    }

    @Override
    public final void run(@NotNull ProgressIndicator indicator) {
      myHandler.setModalityState(indicator.getModalityState());
      myDelegate.run(indicator);
    }

    @Override
    public void execute(ProgressIndicator indicator) {
      addListeners(this, indicator);
      BzrHandlerUtil.runInCurrentThread(myHandler, indicator, false, myTitle);
    }

    @Override
    public void dispose() {
      Disposer.dispose(myDelegate);
    }
  }

  /**
   * Does the work which is common for BackgrounableTask and ModalTask.
   * Actually - starts a timer which checks if current progress indicator is cancelled.
   * If yes, kills the BzrHandler.
   */
  private static class BzrTaskDelegate implements Disposable {
    private BzrHandler myHandler;
    private ProgressIndicator myIndicator;
    private TaskExecution myTask;
    private Timer myTimer;
    private Project myProject;

    public BzrTaskDelegate(Project project, BzrHandler handler, TaskExecution task) {
      myProject = project;
      myHandler = handler;
      myTask = task;
      Disposer.register(myProject, this);
    }

    public void run(ProgressIndicator indicator) {
      myIndicator = indicator;
      myTimer = new Timer();
      myTimer.schedule(new TimerTask() {
        @Override
        public void run() {
          if (myIndicator != null && myIndicator.isCanceled()) {
            try {
              if (myHandler != null) {
                myHandler.destroyProcess();
              }
            } finally {
              Disposer.dispose(BzrTaskDelegate.this);
            }
          }
        }
      }, 0, 200);
      myTask.execute(indicator);
    }

    public void dispose() {
      if (myTimer != null) {
        myTimer.cancel();
      }
    }
  }

}
