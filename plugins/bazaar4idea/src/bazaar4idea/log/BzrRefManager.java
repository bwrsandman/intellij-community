package bazaar4idea.log;

import bazaar4idea.BzrBranch;
import bazaar4idea.BzrRemoteBranch;
import bazaar4idea.repo.BzrBranchTrackInfo;
import bazaar4idea.repo.BzrRemote;
import bazaar4idea.repo.BzrRepository;
import com.intellij.dvcs.repo.RepositoryManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.vcs.log.RefGroup;
import com.intellij.vcs.log.VcsLogRefManager;
import com.intellij.vcs.log.VcsRef;
import com.intellij.vcs.log.VcsRefType;
import com.intellij.vcs.log.impl.SingletonRefGroup;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * @author Kirill Likhodedov
 */
public class BzrRefManager implements VcsLogRefManager {

  private static final Color HEAD_COLOR = new JBColor(new Color(0xf1ef9e), new Color(113, 111, 64));
  private static final Color LOCAL_BRANCH_COLOR = new JBColor(new Color(0x75eec7), new Color(0x0D6D4F));
  private static final Color REMOTE_BRANCH_COLOR = new JBColor(new Color(0xbcbcfc), new Color(0xbcbcfc).darker().darker());
  private static final Color TAG_COLOR = JBColor.WHITE;

  public static final VcsRefType HEAD = new SimpleRefType(true, HEAD_COLOR);
  public static final VcsRefType LOCAL_BRANCH = new SimpleRefType(true, LOCAL_BRANCH_COLOR);
  public static final VcsRefType REMOTE_BRANCH = new SimpleRefType(true, REMOTE_BRANCH_COLOR);
  public static final VcsRefType TAG = new SimpleRefType(false, TAG_COLOR);

  // first has the highest priority
  private static final List<VcsRefType> REF_TYPE_PRIORITIES = Arrays.asList(HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG);

  // -1 => higher priority
  public static final Comparator<VcsRefType> REF_TYPE_COMPARATOR = new Comparator<VcsRefType>() {
    @Override
    public int compare(VcsRefType type1, VcsRefType type2) {
      int p1 = REF_TYPE_PRIORITIES.indexOf(type1);
      int p2 = REF_TYPE_PRIORITIES.indexOf(type2);
      return p1 - p2;
    }
  };

  private static final String MASTER = "master";
  private static final String ORIGIN_MASTER = "origin/master";
  private static final Logger LOG = Logger.getInstance(BzrRefManager.class);

  @NotNull private final RepositoryManager<BzrRepository> myRepositoryManager;

    // -1 => higher priority, i. e. the ref will be displayed at the left
  private final Comparator<VcsRef> REF_COMPARATOR = new Comparator<VcsRef>() {
    public int compare(VcsRef ref1, VcsRef ref2) {
      VcsRefType type1 = ref1.getType();
      VcsRefType type2 = ref2.getType();

      int typeComparison = REF_TYPE_COMPARATOR.compare(type1, type2);
      if (typeComparison != 0) {
        return typeComparison;
      }

      //noinspection UnnecessaryLocalVariable
      VcsRefType type = type1; // common type
      if (type == LOCAL_BRANCH) {
        if (ref1.getName().equals(MASTER)) {
          return -1;
        }
        if (ref2.getName().equals(MASTER)) {
          return 1;
        }
        return ref1.getName().compareTo(ref2.getName());
      }

      if (type == REMOTE_BRANCH) {
        if (ref1.getName().equals(ORIGIN_MASTER)) {
          return -1;
        }
        if (ref2.getName().equals(ORIGIN_MASTER)) {
          return 1;
        }
        return ref1.getName().compareTo(ref2.getName());
      }

      return ref1.getName().compareTo(ref2.getName());
    }
  };

  public BzrRefManager(@NotNull RepositoryManager<BzrRepository> repositoryManager) {
    myRepositoryManager = repositoryManager;
  }

  @NotNull
  @Override
  public List<VcsRef> sort(Collection<VcsRef> refs) {
    ArrayList<VcsRef> list = new ArrayList<VcsRef>(refs);
    Collections.sort(list, REF_COMPARATOR);
    return list;
  }

  @NotNull
  @Override
  public List<RefGroup> group(Collection<VcsRef> refs) {
    List<RefGroup> simpleGroups = ContainerUtil.newArrayList();
    List<VcsRef> localBranches = ContainerUtil.newArrayList();
    List<VcsRef> trackedBranches = ContainerUtil.newArrayList();
    MultiMap<BzrRemote, VcsRef> remoteRefGroups = MultiMap.create();

    MultiMap<VirtualFile, VcsRef> refsByRoot = groupRefsByRoot(refs);
    for (Map.Entry<VirtualFile, Collection<VcsRef>> entry : refsByRoot.entrySet()) {
      VirtualFile root = entry.getKey();
      Collection<VcsRef> refsInRoot = entry.getValue();

      BzrRepository repository = myRepositoryManager.getRepositoryForRoot(root);
      if (repository == null) {
        LOG.warn("No repository for root: " + root);
        continue;
      }

      Set<String> locals = getLocalBranches(repository);

      for (VcsRef ref : refsInRoot) {
        if (ref.getType() == HEAD) {
          simpleGroups.add(new SingletonRefGroup(ref));
          continue;
        }

        String refName = ref.getName();
        if (locals.contains(refName)) {
          localBranches.add(ref);
        }
        else {
          LOG.warn("Didn't find ref neither in local nor in remote branches: " + ref);
        }
      }
    }

    List<RefGroup> result = ContainerUtil.newArrayList();
    result.addAll(simpleGroups);
    result.add(new LogicalRefGroup("Local", localBranches));
    result.add(new LogicalRefGroup("Tracked", trackedBranches));
    for (Map.Entry<BzrRemote, Collection<VcsRef>> entry : remoteRefGroups.entrySet()) {
      final BzrRemote remote = entry.getKey();
      final Collection<VcsRef> branches = entry.getValue();
      result.add(new RemoteRefGroup(remote, branches));
    }
    return result;
  }

  private static Set<String> getLocalBranches(BzrRepository repository) {
    return ContainerUtil.map2Set(repository.getBranches().getLocalBranches(), new Function<BzrBranch, String>() {
      @Override
      public String fun(BzrBranch branch) {
        return branch.getName();
      }
    });
  }

  @NotNull
  private static MultiMap<VirtualFile, VcsRef> groupRefsByRoot(@NotNull Iterable<VcsRef> refs) {
    MultiMap<VirtualFile, VcsRef> grouped = MultiMap.create();
    for (VcsRef ref : refs) {
      grouped.putValue(ref.getRoot(), ref);
    }
    return grouped;
  }

  private static class SimpleRefType implements VcsRefType {
    private final boolean myIsBranch;
    @NotNull private final Color myColor;

    public SimpleRefType(boolean isBranch, @NotNull Color color) {
      myIsBranch = isBranch;
      myColor = color;
    }

    @Override
    public boolean isBranch() {
      return myIsBranch;
    }

    @NotNull
    @Override
    public Color getBackgroundColor() {
      return myColor;
    }
  }

  private static class LogicalRefGroup implements RefGroup {
    private final String myGroupName;
    private final List<VcsRef> myRefs;

    private LogicalRefGroup(String groupName, List<VcsRef> refs) {
      myGroupName = groupName;
      myRefs = refs;
    }

    @Override
    public boolean isExpanded() {
      return true;
    }

    @NotNull
    @Override
    public String getName() {
      return myGroupName;
    }

    @NotNull
    @Override
    public List<VcsRef> getRefs() {
      return myRefs;
    }

    @NotNull
    @Override
    public Color getBgColor() {
      return HEAD_COLOR;
    }
  }

  private class RemoteRefGroup implements RefGroup {
    private final BzrRemote myRemote;
    private final Collection<VcsRef> myBranches;

    public RemoteRefGroup(BzrRemote remote, Collection<VcsRef> branches) {
      myRemote = remote;
      myBranches = branches;
    }

    @Override
    public boolean isExpanded() {
      return false;
    }

    @NotNull
    @Override
    public String getName() {
      return myRemote.getName() + "/...";
    }

    @NotNull
    @Override
    public List<VcsRef> getRefs() {
      return sort(myBranches);
    }

    @NotNull
    @Override
    public Color getBgColor() {
      return REMOTE_BRANCH_COLOR;
    }

  }
}
