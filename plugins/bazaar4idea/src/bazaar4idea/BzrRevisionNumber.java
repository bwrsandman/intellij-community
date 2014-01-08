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
package bazaar4idea;

import bazaar4idea.commands.BzrCommand;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.commands.BzrSimpleHandler;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.Date;
import java.util.StringTokenizer;

/**
 * Bazaar revision number
 */
public class BzrRevisionNumber implements ShortVcsRevisionNumber {

  @NotNull private final int myRevNo;
  /**
   * the date when revision created
   */
  @NotNull private final Date myTimestamp;

  private static final Logger LOG = Logger.getInstance(BzrRevisionNumber.class);

  /**
   * A constructor from version. The current date is used.
   *
   * @param version the version number.
   */
  public BzrRevisionNumber(@NonNls @NotNull String version) {
    // TODO review usages
    myRevNo =  StringUtil.parseInt(version, 0);
    myTimestamp = new Date();
  }

  /**
   * A constructor from version and time
   *
   * @param version   the version number
   * @param timeStamp the time when the version has been created
   */
  public BzrRevisionNumber(@NotNull String version, @NotNull Date timeStamp) {
    myTimestamp = timeStamp;
    myRevNo = StringUtil.parseInt(version, 0);
  }

  @NotNull
  public String asString() {
    return Integer.toString(myRevNo);
  }

  /**
   * @return revision time
   */
  @NotNull
  public Date getTimestamp() {
    return myTimestamp;
  }

  /**
   * @return revision number
   */
  @NotNull
  public int getRev() {
    return myRevNo;
  }

  /**
   * {@inheritDoc}
   */
  public int compareTo(VcsRevisionNumber crev) {
    if (this == crev) return 0;

    if (crev instanceof BzrRevisionNumber) {
      BzrRevisionNumber other = (BzrRevisionNumber)crev;
      if  (myRevNo == other.myRevNo) {
        return 0;
      } else if (myRevNo > other.myRevNo) {
        return -1; // the compared revision is my ancestor
      } else if (myRevNo < other.myRevNo) {
        return 1;  // I am an ancestor of the compared revision
      }
      if (other.myTimestamp != null) {
        return myTimestamp.compareTo(other.myTimestamp);
      }
    }
    return -1;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if ((obj == null) || (obj.getClass() != getClass())) return false;

    BzrRevisionNumber test = (BzrRevisionNumber)obj;
    // TODO normalize revision string?
    return myRevNo == test.myRevNo;
  }

  /**
   * Resolve revision number for the specified revision
   *
   * @param project a project
   * @param vcsRoot a vcs root
   * @param rev     a revision expression
   * @return a resolved revision number with correct time
   * @throws VcsException if there is a problem with running git
   */
  public static BzrRevisionNumber resolve(Project project, VirtualFile vcsRoot, @NonNls String rev) throws VcsException {
    BzrSimpleHandler h = new BzrSimpleHandler(project, vcsRoot, BzrCommand.VERSION_INFO);
    //h.setSilent(true);
    h.addParameters(rev);
    h.endOptions();
    final String output = h.run();
    return parseRevlistOutputAsRevisionNumber(h, output);
  }

  @NotNull
  public static BzrRevisionNumber parseRevlistOutputAsRevisionNumber(@NotNull BzrSimpleHandler h, @NotNull String output) {
    StringTokenizer tokenizer = new StringTokenizer(output, "\n\r \t", false);
    LOG.assertTrue(tokenizer.hasMoreTokens(), "No required tokens in the output: \n" + output);
    Date timestamp = BzrUtil.parseTimestampWithNFEReport(tokenizer.nextToken(), h, output);
    return new BzrRevisionNumber(tokenizer.nextToken(), timestamp);
  }

  @Override
  public String toString() {
    return Integer.toString(myRevNo);
  }

  @Override
  public String toShortString() {
    return this.toString();
  }
}
