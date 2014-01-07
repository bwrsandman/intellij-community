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
package bazaar4idea.repo;

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrUtil;
import bazaar4idea.branch.BzrBranchesCollection;
import com.intellij.dvcs.repo.RepositoryImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import bazaar4idea.BzrLocalBranch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;

/**
 * @author Kirill Likhodedov
 */
public class BzrRepositoryImpl extends RepositoryImpl implements BzrRepository, Disposable {

  @NotNull private final BzrPlatformFacade myPlatformFacade;
  @NotNull private final BzrRepositoryReader myReader;
  @NotNull private final VirtualFile myBzrDir;
  @Nullable private final BzrUntrackedFilesHolder myUntrackedFilesHolder;

  @NotNull private volatile BzrRepoInfo myInfo;

  /**
   * Get the BzrRepository instance from the {@link BzrRepositoryManager}.
   * If you need to have an instance of BzrRepository for a repository outside the project, use
   * {@link #getLightInstance(VirtualFile, Project, bazaar4idea.BzrPlatformFacade, Disposable)}
   */
  @SuppressWarnings("ConstantConditions")
  protected BzrRepositoryImpl(@NotNull VirtualFile rootDir,
                              @NotNull BzrPlatformFacade facade,
                              @NotNull Project project,
                              @NotNull Disposable parentDisposable,
                              final boolean light) {
    super(project, rootDir, parentDisposable);
    myPlatformFacade = facade;
    myBzrDir = BzrUtil.findBzrDir(rootDir);
    assert myBzrDir != null : ".bzr directory wasn't found under " + rootDir.getPresentableUrl();
    myReader = new BzrRepositoryReader(VfsUtilCore.virtualToIoFile(myBzrDir));
    if (!light) {
      myUntrackedFilesHolder = new BzrUntrackedFilesHolder(this);
      Disposer.register(this, myUntrackedFilesHolder);
    }
    else {
      myUntrackedFilesHolder = null;
    }
    update();
  }

  /**
   * Returns the temporary light instance of BzrRepository.
   * It lacks functionality of auto-updating BzrRepository on Bazaar internal files change, and also stored a stub instance of
   * {@link BzrUntrackedFilesHolder}.
   */
  @NotNull
  public static BzrRepository getLightInstance(@NotNull VirtualFile root, @NotNull Project project, @NotNull BzrPlatformFacade facade,
                                               @NotNull Disposable parentDisposable) {
    return new BzrRepositoryImpl(root, facade, project, parentDisposable, true);
  }

  /**
   * Returns the full-functional instance of BzrRepository - with UntrackedFilesHolder and BzrRepositoryUpdater.
   * This is used for repositories registered in project, and should be optained via {@link BzrRepositoryManager}.
   */
  @NotNull
  public static BzrRepository getFullInstance(@NotNull VirtualFile root, @NotNull Project project, @NotNull BzrPlatformFacade facade,
                                              @NotNull Disposable parentDisposable) {
    BzrRepositoryImpl repository = new BzrRepositoryImpl(root, facade, project, parentDisposable, false);
    repository.myUntrackedFilesHolder.setupVfsListener(project);  //myUntrackedFilesHolder cannot be null because it is not light instance
    repository.setupUpdater();
    return repository;
  }

  private void setupUpdater() {
    BzrRepositoryUpdater updater = new BzrRepositoryUpdater(this);
    Disposer.register(this, updater);
  }


  @NotNull
  @Override
  public VirtualFile getBzrDir() {
    return myBzrDir;
  }

  @Override
  @NotNull
  public BzrUntrackedFilesHolder getUntrackedFilesHolder() {
    if (myUntrackedFilesHolder == null) {
      throw new IllegalStateException("Using untracked files holder with light git repository instance " + this);
    }
    return myUntrackedFilesHolder;
  }

  @Override
  @NotNull
  public BzrRepoInfo getInfo() {
    return myInfo;
  }

  @Override
  @Nullable
  public BzrLocalBranch getCurrentBranch() {
    return myInfo.getCurrentBranch();
  }

  @Nullable
  @Override
  public String getCurrentRevision() {
    return myInfo.getCurrentRevision();
  }

  @NotNull
  @Override
  public State getState() {
    return myInfo.getState();
  }

  /**
   * @return local and remote branches in this repository.
   */
  @Override
  @NotNull
  public BzrBranchesCollection getBranches() {
    BzrRepoInfo info = myInfo;
    return new BzrBranchesCollection(info.getLocalBranches(), info.getRemoteBranches());
  }

  @Override
  @NotNull
  public Collection<BzrRemote> getRemotes() {
    return myInfo.getRemotes();
  }

  @Override
  @NotNull
  public Collection<BzrBranchTrackInfo> getBranchTrackInfos() {
    return myInfo.getBranchTrackInfos();
  }

  @Override
  public boolean isRebaseInProgress() {
    return getState() == State.REBASING;
  }

  @Override
  public boolean isOnBranch() {
    return getState() != State.DETACHED && getState() != State.REBASING;
  }

  @Override
  public boolean isFresh() {
    return getCurrentRevision() == null;
  }

  @Override
  public void update() {
    myInfo = readRepoInfo(this, myPlatformFacade, myReader, myInfo);
  }

  @NotNull
  private static BzrRepoInfo readRepoInfo(@NotNull BzrRepository repository, @NotNull BzrPlatformFacade platformFacade,
                                          @NotNull BzrRepositoryReader reader, @Nullable BzrRepoInfo previousInfo) {
    File configFile = new File(new File(VfsUtilCore.virtualToIoFile(repository.getBzrDir()), "branch"), "branch.conf");
    BzrConfig config = BzrConfig.read(platformFacade, configFile);
    Collection<BzrRemote> remotes = config.parseRemotes();
    TempState tempState = readRepository(reader, remotes);
    Collection<BzrBranchTrackInfo> trackInfos = config.parseTrackInfos(tempState.myBranches.getLocalBranches(),
                                                                       tempState.myBranches.getRemoteBranches());
    BzrRepoInfo info = new BzrRepoInfo(tempState.myCurrentBranch, tempState.myCurrentRevision, tempState.myState,
                                       remotes, tempState.myBranches.getLocalBranches(),
                                       tempState.myBranches.getRemoteBranches(), trackInfos);
    notifyListeners(repository, previousInfo, info);
    return info;
  }

  private static TempState readRepository(BzrRepositoryReader reader, @NotNull Collection<BzrRemote> remotes) {
    return new TempState(reader.readState(), reader.readCurrentRevision(), reader.readCurrentBranch(), reader.readBranches(remotes));
  }

  // previous info can be null before the first update
  private static void notifyListeners(@NotNull BzrRepository repository, @Nullable BzrRepoInfo previousInfo, @NotNull BzrRepoInfo info) {
    if (Disposer.isDisposed(repository.getProject())) {
      return;
    }
    if (!info.equals(previousInfo)) {
      repository.getProject().getMessageBus().syncPublisher(BAZAAR_REPO_CHANGE).repositoryChanged(repository);
    }
  }

  @NotNull
  @Override
  public String toLogString() {
    return String.format("BzrRepository " + getRoot() + " : " + myInfo);
  }

  private static class TempState {
    private final State myState;
    private final String myCurrentRevision;
    private final BzrLocalBranch myCurrentBranch;
    private final BzrBranchesCollection myBranches;

    public TempState(@NotNull State state, @Nullable String currentRevision, @Nullable BzrLocalBranch currentBranch,
                     @NotNull BzrBranchesCollection branches) {
      myState = state;
      myCurrentRevision = currentRevision;
      myCurrentBranch = currentBranch;
      myBranches = branches;
    }
  }

}
