/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package bazaar4idea.repo

import com.intellij.openapi.application.PluginPathManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.VcsTestUtil
import com.intellij.util.Processor
import bazaar4idea.BzrBranch
import bazaar4idea.branch.BzrBranchesCollection
import bazaar4idea.test.BzrLightTest
import org.jetbrains.annotations.NotNull
import org.junit.After
import org.junit.Before
import org.junit.Test

import static junit.framework.Assert.*;

/**
 * @author Kirill Likhodedov
 */
public class BzrRepositoryReaderTest extends BzrLightTest {

  private BzrRepositoryReader myRepositoryReader;
  private File myBzrDir;
  private Collection<BzrTestBranch> myLocalBranches;
  private Collection<BzrTestBranch> myRemoteBranches;

  @Before
  void setUp() {
    super.setUp();

    File myTempDir = new File(myTestRoot, "test")
    myTempDir.mkdir()

    File pluginRoot = new File(PluginPathManager.getPluginHomePath("bazaar4idea"));
    File dataDir = new File(new File(pluginRoot, "testData"), "repo");

    FileUtil.copyDir(dataDir, myTempDir);
    myBzrDir = new File(myTempDir, ".bzr");
    FileUtil.rename(new File(myTempDir, "dot_bzr"), myBzrDir);
    assertTrue(myBzrDir.exists());
    myRepositoryReader = new BzrRepositoryReader(myBzrDir);
    
    myLocalBranches = readBranches(true);
    myRemoteBranches = readBranches(false);
  }

  @After
  void tearDown() {
    super.tearDown();
  }
  
  @Test
  public void testHEAD() {
    assertEquals("0e1d130689bc52f140c5c374aa9cc2b8916c0ad7", myRepositoryReader.readCurrentRevision());
  }
  
  @Test
  public void testCurrentBranch() {
    assertBranch(myRepositoryReader.readCurrentBranch(), new BzrTestBranch("master", "0e1d130689bc52f140c5c374aa9cc2b8916c0ad7"));
  }
  
  @Test
  public void testBranches() {
    def remotes = BzrConfig.read(myPlatformFacade, new File(myBzrDir, "config")).parseRemotes();
    BzrBranchesCollection branchesCollection = myRepositoryReader.readBranches(remotes);
    BzrBranch currentBranch = myRepositoryReader.readCurrentBranch();
    Collection<BzrBranch> localBranches = branchesCollection.getLocalBranches();
    Collection<BzrBranch> remoteBranches = branchesCollection.getRemoteBranches();
    
    assertBranch(currentBranch, new BzrTestBranch("master", "0e1d130689bc52f140c5c374aa9cc2b8916c0ad7"));
    assertBranches(localBranches, myLocalBranches);
    assertBranches(remoteBranches, myRemoteBranches);
  }

  private static void assertBranches(Collection<BzrBranch> actualBranches, Collection<BzrTestBranch> expectedBranches) {
    VcsTestUtil.assertEqualCollections(actualBranches, expectedBranches, new VcsTestUtil.EqualityChecker<BzrBranch, BzrTestBranch>() {
      @Override
      public boolean areEqual(@NotNull BzrBranch actual, @NotNull BzrTestBranch expected) {
        return branchesAreEqual(actual, expected);
      }
    });
  }

  private Collection<BzrTestBranch> readBranches(boolean local) throws IOException {
    final Collection<BzrTestBranch> branches = new ArrayList<BzrTestBranch>();
    final File refsHeads = new File(new File(myBzrDir, "refs"), local ? "heads" : "remotes");
    FileUtil.processFilesRecursively(refsHeads, new Processor<File>() {
      @Override
      public boolean process(File file) {
        if (file.equals(refsHeads)) { // don't process the root
          return true;
        }
        if (file.isDirectory()) { // don't process dirs
          return true;
        }
        String relativePath = FileUtil.getRelativePath(refsHeads, file);
        if (relativePath == null) {
          return true;
        }
        String name = FileUtil.toSystemIndependentName(relativePath);
        BzrTestBranch branch = null;
        try {
          branch = new BzrTestBranch(name, FileUtil.loadFile(file));
        }
        catch (IOException e) {
          fail(e.toString());
          e.printStackTrace();
        }
        if (!branches.contains(branch)) {
          branches.add(branch);
        }
        return true;
      }
    });

    // read from packed-refs, these have less priority, so the won't overwrite hashes from branch files
    String packedRefs = FileUtil.loadFile(new File(myBzrDir, "packed-refs"));
    for (String ref : packedRefs.split("\n")) {
      String[] refAndName = ref.split(" ");
      String name = refAndName[1];
      String prefix = local ? "refs/heads/" : "refs/remotes/";
      if (name.startsWith(prefix)) {
        BzrTestBranch branch = new BzrTestBranch(name.substring(prefix.length()), refAndName[0]);
        if (!branches.contains(branch)) {
          branches.add(branch);
        }
      }
    }
    return branches;
  }
  
  private static void assertBranch(BzrBranch actual, BzrTestBranch expected) {
    assertTrue(String.format("Branches are not equal. Actual: %s:%sExpected: %s", actual.getName(), actual.getHash(), expected), branchesAreEqual(actual, expected));
  }

  private static boolean branchesAreEqual(BzrBranch actual, BzrTestBranch expected) {
    return actual.getName().equals(expected.getName()) && actual.getHash().equals(expected.getHash());
  }
  
  private static class BzrTestBranch {
    private final String myName;
    private final String myHash;

    private BzrTestBranch(String name, String hash) {
      myName = name.trim();
      myHash = hash.trim();
    }

    String getName() {
      return myName;
    }
    
    String getHash() {
      return myHash;
    }

    @Override
    public String toString() {
      return myName + ":" + myHash;
    }

    @Override
    public boolean equals(Object o) {
      BzrTestBranch branch = (BzrTestBranch)o;
      if (myName != null ? !myName.equals(branch.myName) : branch.myName != null) return false;
      return true;
    }

    @Override
    public int hashCode() {
      return myName != null ? myName.hashCode() : 0;
    }
  }

}
