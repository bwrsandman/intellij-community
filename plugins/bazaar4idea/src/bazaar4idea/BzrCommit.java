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
package bazaar4idea;

import bazaar4idea.log.BzrContentRevisionFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.VcsUser;
import com.intellij.vcs.log.impl.VcsFullCommitDetailsImpl;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a Bazaar commit with its meta information (hash, author, message, etc.), its parents and the {@link Change changes}.
 *
 * @author Kirill Likhodedov
 */
public final class BzrCommit extends VcsFullCommitDetailsImpl {

  public BzrCommit(final Project project,
                   @NotNull Hash hash,
                   @NotNull List<Hash> parents,
                   long time,
                   @NotNull VirtualFile root,
                   @NotNull String subject,
                   @NotNull VcsUser author,
                   @NotNull String message,
                   @NotNull VcsUser committer,
                   long authorTime,
                   @NotNull List<Change> changes) {
    super(hash, parents, time, root, subject, author, message, committer, authorTime, changes,
          BzrContentRevisionFactory.getInstance(project));

  }
}
