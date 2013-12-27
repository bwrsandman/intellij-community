/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package bazaar4idea.history;

import bazaar4idea.BzrUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.containers.ContainerUtil;
import bazaar4idea.test.BzrTest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.*;

import static bazaar4idea.history.BzrLogParser.*;
import static bazaar4idea.history.BzrLogParser.BzrLogOption.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Test for {@link BzrLogParser}.
 *
 * @author Kirill Likhodedov
 * @deprecated Use {@link BzrLightTest}
 */
@Deprecated
public class BzrLogParserTest extends BzrTest {

  public static final BzrLogOption[] BAZAAR_LOG_OPTIONS =
    new BzrLogOption[]{HASH, COMMIT_TIME, AUTHOR_NAME, AUTHOR_TIME, AUTHOR_EMAIL, COMMITTER_NAME,
      COMMITTER_EMAIL, SUBJECT, BODY, PARENTS, PARENTS, RAW_BODY
    };
  private VirtualFile myRoot;
  private BzrLogParser myParser;
  private BzrTestLogRecord myRecord;

  private static final BzrTestLogRecord RECORD1 = new BzrTestLogRecord(ContainerUtil.<BzrTestLogRecordInfo, Object>immutableMapBuilder()
    .put(BzrTestLogRecordInfo.HASH,         "2c815939f45fbcfda9583f84b14fe9d393ada790")
    .put(BzrTestLogRecordInfo.AUTHOR_TIME,  new Date(1317027817L * 1000))
    .put(BzrTestLogRecordInfo.AUTHOR_NAME,  "John Doe")
    .put(BzrTestLogRecordInfo.AUTHOR_EMAIL, "John.Doe@example.com")
    .put(BzrTestLogRecordInfo.COMMIT_TIME,  new Date(1315471452L * 1000))
    .put(BzrTestLogRecordInfo.COMMIT_NAME,  "Bob Smith")
    .put(BzrTestLogRecordInfo.COMMIT_EMAIL, "Bob@site.com")
    .put(BzrTestLogRecordInfo.SUBJECT,      "Commit message")
    .put(BzrTestLogRecordInfo.BODY,         "Description goes here\n" +
                                            "\n" + // empty line
                                            "Then comes a long long description.\n" +
                                            "Probably multilined.")
    .put(BzrTestLogRecordInfo.PARENTS,      new String[] {
                                              "c916c63b89d8fa81ebf23cc5cbcdb75e115623c7",
                                              "7c1298fd1f93df414ce0d87128532f819de2cbd4"})
    .put(BzrTestLogRecordInfo.CHANGES,      new BzrTestChange[]{
                                              BzrTestChange.moved("file2", "file3"),
                                              BzrTestChange.added("readme.txt"),
                                              BzrTestChange.modified("src/CClass.java"),
                                              BzrTestChange.deleted("src/ChildAClass.java")})
    .build());

  private static final BzrTestLogRecord RECORD2 = new BzrTestLogRecord(ContainerUtil.<BzrTestLogRecordInfo, Object>immutableMapBuilder()
    .put(BzrTestLogRecordInfo.HASH,         "c916c63b89d8fa81ebf23cc5cbcdb75e115623c7")
    .put(BzrTestLogRecordInfo.AUTHOR_TIME,  new Date(1317027817L * 1000))
    .put(BzrTestLogRecordInfo.AUTHOR_NAME,  "John Doe")
    .put(BzrTestLogRecordInfo.AUTHOR_EMAIL, "John.Doe@example.com")
    .put(BzrTestLogRecordInfo.COMMIT_TIME,  new Date(1315471452L * 1000))
    .put(BzrTestLogRecordInfo.COMMIT_NAME,  "John Doe")
    .put(BzrTestLogRecordInfo.COMMIT_EMAIL, "John.Doe@example.com")
    .put(BzrTestLogRecordInfo.SUBJECT,      "Commit message")
    .put(BzrTestLogRecordInfo.BODY,         "Small description")
    .put(BzrTestLogRecordInfo.PARENTS,      new String[] { "7c1298fd1f93df414ce0d87128532f819de2cbd4" })
    .put(BzrTestLogRecordInfo.CHANGES,      new BzrTestChange[] { BzrTestChange.modified("src/CClass.java") })
    .build());

  private static final BzrTestLogRecord RECORD3 = new BzrTestLogRecord(ContainerUtil.<BzrTestLogRecordInfo, Object>immutableMapBuilder()
    .put(BzrTestLogRecordInfo.HASH,         "c916c63b89d8fa81ebf23cc5cbcdb75e115623c7")
    .put(BzrTestLogRecordInfo.AUTHOR_TIME, new Date(1317027817L * 1000))
    .put(BzrTestLogRecordInfo.AUTHOR_NAME,  "John Doe")
    .put(BzrTestLogRecordInfo.AUTHOR_EMAIL, "John.Doe@example.com")
    .put(BzrTestLogRecordInfo.COMMIT_TIME,  new Date(1315471452L * 1000))
    .put(BzrTestLogRecordInfo.COMMIT_NAME,  "John Doe")
    .put(BzrTestLogRecordInfo.COMMIT_EMAIL, "John.Doe@example.com")
    .put(BzrTestLogRecordInfo.SUBJECT,      "Commit message")
    .put(BzrTestLogRecordInfo.BODY,         "Small description")
    .put(BzrTestLogRecordInfo.PARENTS,      new String[] { "7c1298fd1f93df414ce0d87128532f819de2cbd4" })
    .put(BzrTestLogRecordInfo.CHANGES,      new BzrTestChange[] { BzrTestChange.modified("src/CClass.java") })
    .build());
  public static final List<BzrTestLogRecord> ALL_RECORDS = Arrays.asList(RECORD1, RECORD2, RECORD3);


  @BeforeMethod
  protected void setUp(Method testMethod) throws Exception {
    super.setUp(testMethod);
    myRoot = new LightVirtualFile();
    myRecord = RECORD1; // for single record tests
  }
  
  @Test
  public void parseAllWithoutNameStatus() throws VcsException {
    doTestAllRecords(BzrTestLogRecord.NameStatusOption.NONE);
  }

  @Test
  public void parseAllWithName() throws VcsException {
    doTestAllRecords(BzrTestLogRecord.NameStatusOption.NAME);
  }

  @Test
  public void parseAllWithNameStatus() throws VcsException {
    doTestAllRecords(BzrTestLogRecord.NameStatusOption.STATUS);
  }

  private void doTestAllRecords(BzrTestLogRecord.NameStatusOption nameStatusOption) throws VcsException {
    NameStatus option;
    switch (nameStatusOption) {
      case NONE:   option = NameStatus.NONE; break;
      case NAME:   option = NameStatus.NAME; break;
      case STATUS: option = NameStatus.STATUS; break;
      default: throw new AssertionError();
    }

    myParser = new BzrLogParser(myProject, option, BAZAAR_LOG_OPTIONS);
    String output = prepareOutputForAllRecords(nameStatusOption);
    List<BzrLogRecord> actualRecords = myParser.parse(output);
    List<BzrTestLogRecord> expectedRecords = ALL_RECORDS;
    assertAllRecords(actualRecords, expectedRecords, nameStatusOption);
  }

  @Test
  public void parseOneRecordWithoutNameStatus() throws VcsException {
    myParser = new BzrLogParser(myProject, BAZAAR_LOG_OPTIONS);
    doTestOneRecord(BzrTestLogRecord.NameStatusOption.NONE);
  }
  
  @Test
  public void parseOneRecordWithName() throws VcsException {
    myParser = new BzrLogParser(myProject, NameStatus.NAME, BAZAAR_LOG_OPTIONS);
    doTestOneRecord(BzrTestLogRecord.NameStatusOption.NAME);
  }

  @Test
  public void parseOneRecordWithNameStatus() throws VcsException {
    myParser = new BzrLogParser(myProject, NameStatus.STATUS, BAZAAR_LOG_OPTIONS);
    doTestOneRecord(BzrTestLogRecord.NameStatusOption.STATUS);
  }
  
  private void doTestOneRecord(BzrTestLogRecord.NameStatusOption option) throws VcsException {
    String s = myRecord.prepareOutputLine(option);
    BzrLogRecord record = myParser.parseOneRecord(s);
    assertRecord(record, myRecord, option);
  }

  private void assertAllRecords(List<BzrLogRecord> actualRecords,
                                List<BzrTestLogRecord> expectedRecords,
                                BzrTestLogRecord.NameStatusOption nameStatusOption) throws VcsException {
    assertEquals(actualRecords.size(), expectedRecords.size());
    for (int i = 0; i < actualRecords.size(); i++) {
      assertRecord(actualRecords.get(i), expectedRecords.get(i), nameStatusOption);
    }
  }

  private static String prepareOutputForAllRecords(BzrTestLogRecord.NameStatusOption nameStatusOption) {
    StringBuilder sb = new StringBuilder();
    for (BzrTestLogRecord record : ALL_RECORDS) {
      sb.append(record.prepareOutputLine(nameStatusOption)).append("\n");
    }
    return sb.toString();
  }

  private void assertRecord(BzrLogRecord actual, BzrTestLogRecord expected, BzrTestLogRecord.NameStatusOption option) throws VcsException {
    assertEquals(actual.getHash(), expected.getHash());

    assertEquals(actual.getCommitterName(), expected.getCommitterName());
    assertEquals(actual.getCommitterEmail(), expected.getCommitterEmail());
    assertEquals(actual.getDate(), expected.getCommitTime());
    
    assertEquals(actual.getAuthorName(), expected.getAuthorName());
    assertEquals(actual.getAuthorEmail(), expected.getAuthorEmail());
    assertEquals(actual.getAuthorTimeStamp(), expected.getAuthorTime().getTime() / 1000);
    
    assertEquals(actual.getAuthorAndCommitter(), BzrUtil
      .adjustAuthorName(String.format("%s <%s>", expected.getAuthorName(), expected.getAuthorEmail()),
                        String.format("%s <%s>", expected.getCommitterName(), expected.getCommitterEmail())));

    
    assertEquals(actual.getSubject(), expected.getSubject());
    assertEquals(actual.getBody(), expected.getBody());
    assertEquals(actual.getRawBody(), expected.rawBody());

    assertEquals(actual.getParentsHashes(), expected.getParents());

    if (option == BzrTestLogRecord.NameStatusOption.NAME) {
      assertPaths(actual.getFilePaths(myRoot), expected.paths());
    } else if (option == BzrTestLogRecord.NameStatusOption.STATUS) {
      assertPaths(actual.getFilePaths(myRoot), expected.paths());
      assertChanges(actual.parseChanges(myProject, myRoot), expected.changes());
    }
  }

  private void assertPaths(List<FilePath> actualPaths, List<String> expectedPaths) {
    assertEquals(actualPaths.size(), expectedPaths.size(), "Actual: " + actualPaths);
    for (FilePath actualPath : actualPaths) {
      String actualRelPath = FileUtil.getRelativePath(myRoot.getPath(), actualPath.getPath(), '/');
      assertTrue(expectedPaths.contains(actualRelPath));
    }
  }

  private void assertChanges(List<Change> actual, List<BzrTestChange> expected) {
    assertEquals(actual.size(), expected.size());
    for (int i = 0; i < actual.size(); i++) {
      Change actualChange = actual.get(i);
      BzrTestChange expectedChange = expected.get(i);
      assertChange(actualChange, expectedChange);
    }
  }

  private void assertChange(Change actualChange, BzrTestChange expectedChange) {
    assertEquals(actualChange.getType(), expectedChange.myType);
    switch (actualChange.getType()) {
      case MODIFICATION:
      case MOVED:
        assertEquals(getBeforePath(actualChange), expectedChange.myBeforePath);
        assertEquals(getAfterPath(actualChange), expectedChange.myAfterPath);
        return;
      case NEW:
        assertEquals(getAfterPath(actualChange), expectedChange.myAfterPath);
        return;
      case DELETED:
        assertEquals(getBeforePath(actualChange), expectedChange.myBeforePath);
        return;
      default:
        throw new AssertionError();
    } 
  }

  private String getBeforePath(Change actualChange) {
    return FileUtil.getRelativePath(myRoot.getPath(), actualChange.getBeforeRevision().getFile().getPath(), '/');
  }

  private String getAfterPath(Change actualChange) {
    return FileUtil.getRelativePath(myRoot.getPath(), actualChange.getAfterRevision().getFile().getPath(), '/');
  }
  
  private enum BzrTestLogRecordInfo {
    HASH,
    COMMIT_TIME,
    AUTHOR_TIME,
    AUTHOR_NAME,
    AUTHOR_EMAIL,
    COMMIT_NAME,
    COMMIT_EMAIL,
    SUBJECT,
    BODY,
    PARENTS,
    CHANGES
  }

  private static class BzrTestLogRecord {

    private final Map<BzrTestLogRecordInfo, Object> myData;

    BzrTestLogRecord(Map<BzrTestLogRecordInfo, Object> data) {
      myData = data;
    }

    public String getHash() {
      return (String)myData.get(BzrTestLogRecordInfo.HASH);
    }

    public Date getCommitTime() {
      return (Date)myData.get(BzrTestLogRecordInfo.COMMIT_TIME);
    }

    public Date getAuthorTime() {
      return (Date)myData.get(BzrTestLogRecordInfo.AUTHOR_TIME);
    }

    public String getAuthorName() {
      return (String)myData.get(BzrTestLogRecordInfo.AUTHOR_NAME);
    }

    public String getAuthorEmail() {
      return (String)myData.get(BzrTestLogRecordInfo.AUTHOR_EMAIL);
    }

    public String getCommitterName() {
      return (String)myData.get(BzrTestLogRecordInfo.COMMIT_NAME);
    }

    public String getCommitterEmail() {
      return (String)myData.get(BzrTestLogRecordInfo.COMMIT_EMAIL);
    }

    public String getSubject() {
      return (String)myData.get(BzrTestLogRecordInfo.SUBJECT);
    }

    public String getBody() {
      return (String)myData.get(BzrTestLogRecordInfo.BODY);
    }

    public String[] getParents() {
      return (String[])myData.get(BzrTestLogRecordInfo.PARENTS);
    }

    public BzrTestChange[] getChanges() {
      return (BzrTestChange[])myData.get(BzrTestLogRecordInfo.CHANGES);
    }

    String[] shortParents() {
      String[] parents = getParents();
      String[] shortParents = new String[parents.length];
      for (int i = 0 ; i < parents.length; i++) {
        shortParents[i] = parents[i].substring(0, 7);
      }
      return shortParents;
    }

    private String shortParentsAsString() {
      return StringUtil.join(shortParents(), " ");
    }

    private String parentsAsString() {
      return StringUtil.join(getParents(), " ");
    }

    String rawBody() {
      return getSubject() + "\n\n" + getBody();
    }

    List<BzrTestChange> changes() {
      return Arrays.asList(getChanges());
    }

    private String changesAsString() {
      StringBuilder sb = new StringBuilder();
      for (BzrTestChange change : getChanges()) {
        sb.append(change.toOutputString());
      }
      return sb.toString();
    }

    public List<String> paths() {
      List<String> paths = new ArrayList<String>();
      for (BzrTestChange change : getChanges()) {
        switch (change.myType) {
          case MODIFICATION:
          case NEW:
            paths.add(change.myAfterPath);
            break;
          case DELETED:
            paths.add(change.myBeforePath);
            break;
          case MOVED:
            paths.add(change.myAfterPath);
            paths.add(change.myBeforePath);
            break;
          default:
            throw new AssertionError();
        }
      }
      return paths;
    }

    private String pathsAsString() {
      StringBuilder sb = new StringBuilder();
      for (String path : paths()) {
        sb.append(path).append("\n");
      }
      return sb.toString();
    }

    enum NameStatusOption {
      NONE, NAME, STATUS
    }
    
    String prepareOutputLine(NameStatusOption nameStatusOption) {
      StringBuilder sb = new StringBuilder(RECORD_START);
      for (BzrLogOption option : BAZAAR_LOG_OPTIONS) {
        sb.append(optionToValue(option)).append(ITEMS_SEPARATOR);
      }
      sb.append(RECORD_END);

      if (nameStatusOption == NameStatusOption.NAME) {
        sb.append("\n\n").append(pathsAsString());
      }
      else if (nameStatusOption == NameStatusOption.STATUS) {
        sb.append("\n\n").append(changesAsString());
      }

      return sb.toString();
    }

    private String optionToValue(BzrLogOption option) {
      switch (option) {
        case HASH:
          return getHash();
        case SUBJECT:
          return getSubject();
        case BODY:
          return getBody();
        case RAW_BODY:
          return rawBody();
        case COMMIT_TIME:
          return String.valueOf(getCommitTime().getTime() / 1000);
        case AUTHOR_NAME:
          return getAuthorName();
        case AUTHOR_TIME:
          return String.valueOf(getAuthorTime().getTime() / 1000);
        case AUTHOR_EMAIL:
          return getAuthorEmail();
        case COMMITTER_NAME:
          return getCommitterName();
        case COMMITTER_EMAIL:
          return getCommitterEmail();
        case PARENTS:
          return parentsAsString();
        case REF_NAMES:
          break;
        case SHORT_REF_LOG_SELECTOR:
          break;
      }
      throw new AssertionError();
    }

  }
  
  private static class BzrTestChange {
    final Change.Type myType;
    final String myBeforePath;
    final String myAfterPath;

    BzrTestChange(Change.Type type, String beforePath, String afterPath) {
      myAfterPath = afterPath;
      myBeforePath = beforePath;
      myType = type;
    }

    static BzrTestChange added(String path) {
      return new BzrTestChange(Change.Type.NEW, null, path);
    }

    static BzrTestChange deleted(String path) {
      return new BzrTestChange(Change.Type.DELETED, path, null);
    }

    static BzrTestChange modified(String path) {
      return new BzrTestChange(Change.Type.MODIFICATION, path, path);
    }

    static BzrTestChange moved(String before, String after) {
      return new BzrTestChange(Change.Type.MOVED, before, after);
    }
    
    String toOutputString() {
      switch (myType) {
        case MOVED: return outputString("R100", myBeforePath, myAfterPath);
        case MODIFICATION: return outputString("M", myBeforePath, null);
        case DELETED: return outputString("D", myBeforePath, null);
        case NEW: return outputString("A", myAfterPath, null);
        default:
          throw new AssertionError();
      }
    }

    private static String outputString(@NotNull String type, @Nullable String beforePath, @Nullable String afterPath) {
      StringBuilder sb = new StringBuilder();
      sb.append(type).append("\t");
      if (beforePath != null) {
        sb.append(beforePath).append("\t");
      }
      if (afterPath != null) {
        sb.append(afterPath).append("\t");
      }
      sb.append("\n");
      return sb.toString();
    }
  }
  
}
