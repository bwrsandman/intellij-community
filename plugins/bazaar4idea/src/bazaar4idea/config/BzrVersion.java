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
package bazaar4idea.config;

import com.google.common.base.Objects;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import org.jetbrains.annotations.NotNull;

import java.text.ParseException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BzrVersion implements Comparable<BzrVersion> {

  /**
   * Type indicates the type of this git distribution: is it native (unix) or msys or cygwin.
   * Type UNDEFINED means that the type doesn't matter in certain condition.
   */
  public static enum Type {
    UNIX,
    MSYS,
    CYGWIN,
    /** The type doesn't matter or couldn't be detected. */
    UNDEFINED,
    /**
     * Information about Bazaar version is unavailable because the BzrVcs hasn't fully initialized yet, or because Bazaar executable is
     * invalid.
     */
    NULL
  }

  /**
   * Special version with a special Type which indicates, that Bazaar version information is unavailable.
   * Probably, because of invalid executable, or when BzrVcs hasn't fully initialized yet.
   */
  public static final BzrVersion NULL = new BzrVersion(0, 0, 0, Type.NULL);

  private static final Pattern FORMAT = Pattern.compile(
    "(\\d+)\\.(\\d+)\\.(\\d+)(.*)", Pattern.CASE_INSENSITIVE);

  private static final Logger LOG = Logger.getInstance(BzrVersion.class.getName());

  private final int myMajor;
  private final int myMinor;
  private final int myRevision;
  private final Type myType;

  private final int myHashCode;

  public BzrVersion(int major, int minor, int revision, Type type) {
    myMajor = major;
    myMinor = minor;
    myRevision = revision;
    myType = type;
    myHashCode = Objects.hashCode(myMajor, myMinor, myRevision);
  }

  /**
   * Creates new BzrVersion with the UNDEFINED Type which actually means that the type doesn't matter for current purpose.
   */
  public BzrVersion(int major, int minor, int revision) {
    this(major, minor, revision, Type.UNDEFINED);
  }

  /**
   * Parses output of "bzr version" command.
   */
  @NotNull
  public static BzrVersion parse(String output) throws ParseException {
    if (StringUtil.isEmptyOrSpaces(output)) {
      throw new ParseException("Empty bzr version --short output:\n" + output, 0);
    }
    Matcher m = FORMAT.matcher(output.trim());
    if (!m.matches()) {
      throw new ParseException("Unsupported format of bzr version --short output:\n" + output, 0);
    }
    int major = getIntGroup(m, 1);
    int minor = getIntGroup(m, 2);
    int rev = getIntGroup(m, 3);
    boolean msys = (m.groupCount() >= 5) && m.group(5) != null && m.group(5).toLowerCase().contains("msysgit");
    Type type;
    if (SystemInfo.isWindows) {
      type = msys ? Type.MSYS : Type.CYGWIN;
    } else {
      type = Type.UNIX;
    }
    return new BzrVersion(major, minor, rev, type);
  }

  // Utility method used in parsing - checks that the given capture group exists and captured something - then returns the captured value,
  // otherwise returns 0.
  private static int getIntGroup(Matcher matcher, int group) {
    if (group > matcher.groupCount()+1) {
      return 0;
    }
    final String match = matcher.group(group);
    if (match == null) {
      return 0;
    }
    return Integer.parseInt(match);
  }

  @NotNull
  public static BzrVersion identifyVersion(String bzrExecutable) throws TimeoutException, ExecutionException, ParseException {
    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(bzrExecutable);
    commandLine.addParameter("version");
    commandLine.addParameter("--short");
    CapturingProcessHandler handler = new CapturingProcessHandler(commandLine.createProcess(), CharsetToolkit.getDefaultSystemCharset());
    ProcessOutput result = handler.runProcess(30 * 1000);
    if (result.isTimeout()) {
      throw new TimeoutException("Couldn't identify the version of Bazaar - stopped by timeout.");
    }
    if (result.getExitCode() != 0 || !result.getStderr().isEmpty()) {
      LOG.info("getVersion exitCode=" + result.getExitCode() + " errors: " + result.getStderr());
      // anyway trying to parse
      try {
        parse(result.getStdout());
      } catch (ParseException pe) {
        throw new ExecutionException("Errors while executing bzr version --short. exitCode=" + result.getExitCode() +
                                     " errors: " + result.getStderr());
      }
    }
    return parse(result.getStdout());
  }

  /**
   * @return true if the version is supported by the plugin
   */
  public boolean isSupported() {
    return getType() != Type.NULL;// && compareTo(MIN) >= 0;
  }

  /**
   * Note: this class has a natural ordering that is inconsistent with equals.
   * Two BzrVersions are equal if their number versions are equal and if their types are equal.
   * Types are considered equal also if one of them is undefined. Otherwise they are compared.
   */
  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof BzrVersion)) {
      return false;
    }
    BzrVersion other = (BzrVersion) obj;
    if (compareTo(other) != 0) {
      return false;
    }
    if (myType == Type.UNDEFINED || other.myType == Type.UNDEFINED) {
      return true;
    }
    return myType == other.myType;
  }

  /**
   * Hashcode is computed from numbered components of the version. Thus BzrVersions with the same numbered components will be compared
   * by equals, and there the type will be taken into consideration).
   */
  @Override
  public int hashCode() {
    return myHashCode;
  }

  public int compareTo(@NotNull BzrVersion o) {
    if (o.getType() == Type.NULL) {
      return (getType() == Type.NULL ? 0 : 1);
    }
    int d = myMajor - o.myMajor;
    if (d != 0) {
      return d;
    }
    d = myMinor - o.myMinor;
    if (d != 0) {
      return d;
    }
    return myRevision - o.myRevision;
  }

  @Override
  public String toString() {
    final String msysIndicator = (myType == Type.MSYS ? ".msysgit" : "");
    return myMajor + "." + myMinor + "." + myRevision + msysIndicator;
  }

  /**
   * @return true if this version is older or the same than the given one.
   */
  public boolean isOlderOrEqual(final BzrVersion bzrVersion) {
    return bzrVersion != null && compareTo(bzrVersion) <= 0;
  }

  /**
   * @return true if this version is later or the same than the given one.
   */
  public boolean isLaterOrEqual(BzrVersion version) {
    return version != null && compareTo(version) >= 0;
  }

  public Type getType() {
    return myType;
  }

  public boolean isNull() {
    return getType() == Type.NULL;
  }

}
