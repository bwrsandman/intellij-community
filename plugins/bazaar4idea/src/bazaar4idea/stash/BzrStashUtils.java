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
package bazaar4idea.stash;

import bazaar4idea.commands.Bzr;
import bazaar4idea.commands.BzrCommand;
import bazaar4idea.commands.BzrCommandResult;
import bazaar4idea.commands.BzrSimpleHandler;
import bazaar4idea.config.BzrConfigUtil;
import bazaar4idea.repo.BzrRepository;
import bazaar4idea.ui.StashInfo;
import bazaar4idea.util.BzrUIUtil;
import bazaar4idea.util.StringScanner;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;

/**
 * The class contains utilities for creating and removing stashes.
 */
public class BzrStashUtils {

  private static final Logger LOG = Logger.getInstance(BzrStashUtils.class);

  private BzrStashUtils() {
  }

  public static boolean saveStash(@NotNull Bzr bzr, @NotNull BzrRepository repository, final String message) {
    BzrCommandResult result = bzr.stashSave(repository, message);
    return result.success() && !result.getErrorOutputAsJoinedString().contains("No local changes to save");
  }

  public static void loadStashStack(@NotNull Project project, @NotNull VirtualFile root, Consumer<StashInfo> consumer) {
    loadStashStack(project, root, Charset.forName(BzrConfigUtil.getLogEncoding(project, root)), consumer);
  }

  public static void loadStashStack(@NotNull Project project, @NotNull VirtualFile root, final Charset charset,
                                    final Consumer<StashInfo> consumer) {
    BzrSimpleHandler h = new BzrSimpleHandler(project, root, BzrCommand.STASH.readLockingCommand());
    //h.setSilent(true);
    h.addParameters("list");
    String out;
    try {
      h.setCharset(charset);
      out = h.run();
    }
    catch (VcsException e) {
      BzrUIUtil.showOperationError(project, e, h.printableCommandLine());
      return;
    }
    for (StringScanner s = new StringScanner(out); s.hasMoreData();) {
      consumer.consume(new StashInfo(s.boundedToken(':'), s.boundedToken(':'), s.line().trim()));
    }
  }
}
