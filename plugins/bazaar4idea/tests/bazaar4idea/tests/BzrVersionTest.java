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
package bazaar4idea.tests;

import bazaar4idea.config.BzrVersion;
import com.intellij.openapi.util.SystemInfo;
import org.testng.annotations.Test;

import java.lang.reflect.Field;

import static bazaar4idea.config.BzrVersion.Type;
import static org.testng.Assert.*;

/**
 * @author Kirill Likhodedov
 */
public class BzrVersionTest {

  private static final TestBzrVersion[] commonTests = {
    new TestBzrVersion("bzr version 1.6.4", 1, 6, 4, 0),
    new TestBzrVersion("bzr version 1.7.3.3", 1, 7, 3, 3),
    new TestBzrVersion("bzr version 1.7.3.5.gb27be", 1, 7, 3, 5),
    new TestBzrVersion("bzr version 1.7.4-rc1", 1, 7, 4, 0),
    new TestBzrVersion("bzr version 1.7.7.5 (Apple Git-24)", 1, 7, 7, 5)
  };

  private static final TestBzrVersion[] msysTests = {
    new TestBzrVersion("bzr version 1.6.4.msysgit.0", 1, 6, 4, 0),
    new TestBzrVersion("bzr version 1.7.3.3.msysgit.1", 1, 7, 3, 3),
    new TestBzrVersion("bzr version 1.7.3.2.msysgit", 1, 7, 3, 2),
    new TestBzrVersion("bzr version 1.7.3.5.msysgit.gb27be", 1, 7, 3, 5)
  };

  /**
   * Tests that parsing the 'bzr version' command output returns the correct BzrVersion object.
   * Tests on UNIX and on Windows - both CYGWIN and MSYS.
   */
  @Test
  public void testParse() throws Exception {
    if (SystemInfo.isUnix) {
      for (TestBzrVersion test : commonTests) {
        BzrVersion version = BzrVersion.parse(test.output);
        assertEqualVersions(version, test, Type.UNIX);
      }
    }
    else if (SystemInfo.isWindows) {
      for (TestBzrVersion test : commonTests) {
        BzrVersion version = BzrVersion.parse(test.output);
        assertEqualVersions(version, test, Type.CYGWIN);
      }

      for (TestBzrVersion test : msysTests) {
        BzrVersion version = BzrVersion.parse(test.output);
        assertEqualVersions(version, test, Type.MSYS);
      }
    }
  }

  @Test
  public void testEqualsAndCompare() throws Exception {
    BzrVersion v1 = BzrVersion.parse("bzr version 1.6.3");
    BzrVersion v2 = BzrVersion.parse("bzr version 1.7.3");
    BzrVersion v3 = BzrVersion.parse("bzr version 1.7.3");
    BzrVersion v4 = BzrVersion.parse("bzr version 1.7.3.msysgit.0");
    assertFalse(v1.equals(v2));
    assertFalse(v1.equals(v3));
    assertTrue(v2.equals(v3));
    if (SystemInfo.isWindows) {
      assertFalse(v1.equals(v4));
      assertFalse(v2.equals(v4));
      assertFalse(v3.equals(v4));
    }

    assertEquals(v1.compareTo(v2), -1);
    assertEquals(v1.compareTo(v3), -1);
    assertEquals(v1.compareTo(v4), -1);
    assertEquals(v2.compareTo(v3), 0);
    assertEquals(v2.compareTo(v4), 0);
    assertEquals(v3.compareTo(v4), 0);
  }

  // Compares the parsed output and what we've expected.
  // Uses reflection to get private fields of BazaarVersion: we don't need them in code, so no need to trash the class with unused accessors.
  private static void assertEqualVersions(BzrVersion actual, TestBzrVersion expected, Type expectedType) throws Exception {
    Field field = BzrVersion.class.getDeclaredField("myMajor");
    field.setAccessible(true);
    final int major = field.getInt(actual);
    field = BzrVersion.class.getDeclaredField("myMinor");
    field.setAccessible(true);
    final int minor = field.getInt(actual);
    field = BzrVersion.class.getDeclaredField("myRevision");
    field.setAccessible(true);
    final int rev = field.getInt(actual);
    field = BzrVersion.class.getDeclaredField("myPatchLevel");
    field.setAccessible(true);
    final int patch = field.getInt(actual);
    field = BzrVersion.class.getDeclaredField("myType");
    field.setAccessible(true);
    final Type type = (Type) field.get(actual);

    assertEquals(major, expected.major);
    assertEquals(minor, expected.minor);
    assertEquals(rev, expected.rev);
    assertEquals(patch, expected.patch);
    assertEquals(type, expectedType);
  }

  private static class TestBzrVersion {
    private final String output;
    private final int major;
    private final int minor;
    private final int rev;
    private final int patch;

    public TestBzrVersion(String output, int major, int minor, int rev, int patch) {
      this.output = output;
      this.major = major;
      this.minor = minor;
      this.rev = rev;
      this.patch = patch;
    }
  }

}
