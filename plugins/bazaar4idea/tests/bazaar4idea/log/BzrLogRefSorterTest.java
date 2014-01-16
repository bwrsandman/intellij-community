package bazaar4idea.log;

import bazaar4idea.branch.BzrBranchesCollection;
import bazaar4idea.test.BzrTestRepositoryManager;
import com.intellij.mock.MockVirtualFile;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.UsefulTestCase;
import com.intellij.util.Function;
import com.intellij.util.NotNullFunction;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.VcsRef;
import com.intellij.vcs.log.VcsRefType;
import com.intellij.vcs.log.impl.HashImpl;
import com.intellij.vcs.log.impl.VcsRefImpl;
import bazaar4idea.BzrLocalBranch;
import bazaar4idea.BzrRemoteBranch;
import bazaar4idea.repo.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Kirill Likhodedov
 */
public class BzrLogRefSorterTest extends UsefulTestCase {

  public static final MockVirtualFile MOCK_VIRTUAL_FILE = new MockVirtualFile("mockFile");

  public void testEmpty() {
    check(Collections.<VcsRef>emptyList(), Collections.<VcsRef>emptyList());
  }

  public void testSingle() {
    check(given("HEAD"),
          expect("HEAD"));
  }

  public void testHeadIsMoreImportantThanBranch() {
    check(given("master", "HEAD"),
          expect("HEAD", "master"));
  }

  public void testLocalBranchesAreComparedAsStrings() {
    check(given("release", "feature"),
          expect("feature", "release"));
  }

  public void testTagIsTheLessImportant() {
    check(given("tag/v1", "origin/master"),
          expect("origin/master", "tag/v1"));
  }

  public void testMasterIsMoreImportant() {
    check(given("feature", "master"),
          expect("master", "feature"));
  }

  public void testOriginMasterIsMoreImportant() {
    check(given("origin/master", "origin/aaa"),
          expect("origin/master", "origin/aaa"));
  }

  public void testRemoteBranchHavingTrackingBranchIsMoreImportant() {
    check(given("feature", "origin/aaa", "origin/feature"),
          expect("feature", "origin/feature", "origin/aaa"));
  }

  public void testSeveral1() {
    check(given("tag/v1", "feature", "HEAD", "master"),
          expect("HEAD", "master", "feature", "tag/v1"));
  }

  public void testSeveral2() {
    check(given("origin/master", "origin/great_feature", "tag/v1", "release", "HEAD", "master"),
          expect("HEAD", "master", "release", "origin/master", "origin/great_feature", "tag/v1"));
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();

  }

  private static Collection<VcsRef> given(String... refs) {
    return convertToRefs(refs);
  }

  private static List<VcsRef> expect(String... refs) {
    return new ArrayList<VcsRef>(convertToRefs(refs));
  }

  private static List<VcsRef> convertToRefs(String[] refs) {
    return ContainerUtil.map(refs, new Function<String, VcsRef>() {
      @Override
      public VcsRef fun(String name) {
        return ref(name);
      }
    });
  }

  private static VcsRef ref(String name) {
    String randomHash = randomHash();
    if (isHead(name)) {
      return ref(randomHash, name, BzrRefManager.HEAD);
    }
    if (isRemoteBranch(name)) {
      return ref(randomHash, name, BzrRefManager.REMOTE_BRANCH);
    }
    if (isTag(name)) {
      return ref(randomHash, name, BzrRefManager.TAG);
    }
    return ref(randomHash, name, BzrRefManager.LOCAL_BRANCH);
  }

  private static String randomHash() {
    return String.valueOf(new Random().nextInt());
  }

  private static boolean isHead(String name) {
    return name.equals("HEAD");
  }

  private static boolean isTag(String name) {
    return name.startsWith("tag/");
  }

  private static boolean isRemoteBranch(String name) {
    return name.startsWith("origin/");
  }

  private static boolean isLocalBranch(String name) {
    return !isHead(name) && !isTag(name) && !isRemoteBranch(name);
  }

  private static VcsRef ref(String hash, String name, VcsRefType type) {
    return new VcsRefImpl(new NotNullFunction<Hash, Integer>() {
      @NotNull
      @Override
      public Integer fun(Hash hash) {
        return Integer.parseInt(hash.asString().substring(0, Math.min(4, hash.asString().length())), 16);
      }
    }, HashImpl.build(hash), name, type, MOCK_VIRTUAL_FILE);
  }

  private static void check(Collection<VcsRef> unsorted, List<VcsRef> expected) {
    // for the sake of simplicity we check only names of references
    List<VcsRef> actual = sort(unsorted);
    assertEquals("Collections size don't match", expected.size(), actual.size());
    for (int i = 0; i < actual.size(); i++) {
      assertEquals("Incorrect element at place " + i, expected.get(i).getName(), actual.get(i).getName());
    }
  }

  private static List<VcsRef> sort(final Collection<VcsRef> refs) {
    final BzrTestRepositoryManager manager = new BzrTestRepositoryManager();
    manager.add(new MockBzrRepository() {
    });
    return new BzrRefManager(manager).sort(refs);
  }

  // TODO either use the real BzrRepository, or move upwards and make more generic implementation
  private static class MockBzrRepository implements BzrRepository {
    @NotNull
    @Override
    public VirtualFile getBzrDir() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public BzrUntrackedFilesHolder getUntrackedFilesHolder() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public BzrRepoInfo getInfo() {
      throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public BzrLocalBranch getCurrentBranch() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public BzrBranchesCollection getBranches() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public Collection<BzrRemote> getRemotes() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRebaseInProgress() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOnBranch() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public VirtualFile getRoot() {
      return MOCK_VIRTUAL_FILE;
    }

    @NotNull
    @Override
    public String getPresentableUrl() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public Project getProject() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public State getState() {
      throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public String getCurrentRevision() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFresh() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void update() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public String toLogString() {
      throw new UnsupportedOperationException();
    }
  }
}
