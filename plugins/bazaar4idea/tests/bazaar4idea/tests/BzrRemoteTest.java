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

package bazaar4idea.tests;

import bazaar4idea.BzrDeprecatedRemote;
import com.intellij.openapi.util.io.FileUtil;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.TreeSet;

/**
 * Test for Bazaar remotes
 */
public class BzrRemoteTest {
  /**
   * Get output of bzr remote show
   *
   * @param version the version of output
   * @param op      operation to test
   * @return the output text
   * @throws IOException if some problem during loading
   */
  String getOutput(String version, String op) throws IOException {
    return FileUtil.loadTextAndClose(
      new InputStreamReader(getClass().getResourceAsStream("/git4idea/tests/data/bzr-remote-" + op + "-" + version + ".txt"), "UTF-8"));
  }

  @Test
  public void testFindRemote() throws IOException {
    String out161 = getOutput("1_6_1", "show");
    BzrDeprecatedRemote r1 = BzrDeprecatedRemote.parseRemoteInternal("origin", out161);
    assertEquals(r1.name(), "origin");
    assertEquals(r1.fetchUrl(), "bzr@host/dir.bzr");
    assertEquals(r1.pushUrl(), "bzr@host/dir.bzr");
    BzrDeprecatedRemote.Info ri1 = r1.parseInfoInternal(out161);
    TreeSet<String> branches = new TreeSet<String>();
    branches.add("master");
    assertEquals(branches, ri1.trackedBranches());
    assertEquals("master", ri1.getRemoteForLocal("master"));
    String out164 = getOutput("1_6_4", "show");
    BzrDeprecatedRemote r2 = BzrDeprecatedRemote.parseRemoteInternal("origin", out164);
    assertEquals(r2.name(), "origin");
    assertEquals(r2.fetchUrl(), "bzr://host/dir.bzr");
    assertEquals(r2.pushUrl(), "bzr@host/dir.bzr");
    BzrDeprecatedRemote.Info ri2 = r1.parseInfoInternal(out164);
    branches.add("master");
    assertEquals(branches, ri2.trackedBranches());
    assertEquals("master", ri2.getRemoteForLocal("master"));
  }

  @Test
  public void testListRemote() throws IOException {
    String out161 = getOutput("1_6_1", "list");
    List<BzrDeprecatedRemote> list = BzrDeprecatedRemote.parseRemoteListInternal(out161);
    assertEquals(1, list.size());
    BzrDeprecatedRemote r1 = list.get(0);
    assertEquals("origin", r1.name());
    assertEquals("bzr@host/dir.bzr", r1.fetchUrl());
    assertEquals("bzr@host/dir.bzr", r1.pushUrl());
    String out164 = getOutput("1_6_4", "list");
    List<BzrDeprecatedRemote> list2 = BzrDeprecatedRemote.parseRemoteListInternal(out164);
    assertEquals(1, list2.size());
    BzrDeprecatedRemote r2 = list2.get(0);
    assertEquals("bzr://host/dir.bzr", r2.fetchUrl());
    assertEquals("bzr@host/dir.bzr", r2.pushUrl());
  }
}
