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

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * <p>
 *   The descriptor of git command.
 * </p>
 * <p>
 *   It contains policy information about locking which is handled in {@link BzrHandler#runInCurrentThread(java.lang.Runnable)} to prevent
 *   simultaneous Bazaar commands conflict on the index.lock file.
 *   write-commands can't be executed simultaneously, but a write-command doesn't prevent read-commands to execute.
 * </p>
 * <p>
 *   A lock-policy can be different for a single command, for example, {@code git stash} may change the index (and thus should hold the
 *   write lock), which {@code git stash list} doesn't (and therefore no lock is needed).
 * </p>
 */
public class BzrCommand {

  public static final BzrCommand ADD = write("add");
  public static final BzrCommand BLAME = read("blame"); // annotate
  public static final BzrCommand BRANCH = read("branch");
  public static final BzrCommand CHECKOUT = write("checkout");
  public static final BzrCommand COMMIT = write("commit");
  public static final BzrCommand CONFIG = read("config");
  public static final BzrCommand CHERRY_PICK = write("cherry-pick");
  public static final BzrCommand DIFF = read("diff");
  public static final BzrCommand FETCH = read("fetch");  // fetch is a read-command, because it doesn't modify the index
  public static final BzrCommand INIT = write("init");
  public static final BzrCommand INFO = read("info");
  public static final BzrCommand LOG = read("log");
  public static final BzrCommand LS = read("ls");
  public static final BzrCommand MERGE = write("merge");
  public static final BzrCommand MERGE_BASE = read("merge-base");
  public static final BzrCommand MV = read("mv");
  public static final BzrCommand PULL = write("pull");
  public static final BzrCommand PUSH = write("push");
  public static final BzrCommand REBASE = writeSuspendable("rebase");
  public static final BzrCommand REMOTE = read("remote");
  public static final BzrCommand RESET = write("reset");
  public static final BzrCommand REV_PARSE = read("rev-parse");
  public static final BzrCommand RM = write("rm");
  public static final BzrCommand SHOW = read("show");
  public static final BzrCommand STASH = write("stash");
  public static final BzrCommand STATUS = read("status");
  public static final BzrCommand TAG = read("tag");
  public static final BzrCommand UPDATE_INDEX = write("update-index");
  public static final BzrCommand VERSION_INFO = read("version-info");

  /**
   * Name of environment variable that specifies editor for Bazaar
   */
  public static final String BZR_EDITOR_ENV = "BZR_EDITOR";

  enum LockingPolicy {
    READ,
    WRITE,
    WRITE_SUSPENDABLE,
  }

  @NotNull @NonNls private final String myName; // command name passed to git
  @NotNull private final LockingPolicy myLocking; // Locking policy for the command

  private BzrCommand(@NotNull String name, @NotNull LockingPolicy lockingPolicy) {
    myLocking = lockingPolicy;
    myName = name;
  }

  /**
   * Copy constructor with other locking policy.
   */
  private BzrCommand(@NotNull BzrCommand command, @NotNull LockingPolicy lockingPolicy) {
    myName = command.name();
    myLocking = lockingPolicy;
  }

  /**
   * <p>Creates the clone of this git command, but with LockingPolicy different from the default one.</p>
   * <p>This can be used for commands, which are considered to be "write" commands in general, but can be "read" commands when a certain
   *    set of arguments is given ({@code git stash list}, for instance).</p>
   * <p>Use this constructor with care: specifying read-policy on a write operation may result in a conflict during simultaneous
   *    modification of index.</p>
   */
  @NotNull
  public BzrCommand readLockingCommand() {
    return new BzrCommand(this, LockingPolicy.READ);
  }

  @NotNull
  private static BzrCommand read(@NotNull String name) {
    return new BzrCommand(name, LockingPolicy.READ);
  }

  @NotNull
  private static BzrCommand write(@NotNull String name) {
    return new BzrCommand(name, LockingPolicy.WRITE);
  }

  @NotNull
  private static BzrCommand writeSuspendable(@NotNull String name) {
    return new BzrCommand(name, LockingPolicy.WRITE_SUSPENDABLE);
  }

  @NotNull
  public String name() {
    return myName;
  }

  @NotNull
  public LockingPolicy lockingPolicy() {
    return myLocking;
  }

  @Override
  public String toString() {
    return myName;
  }
}
