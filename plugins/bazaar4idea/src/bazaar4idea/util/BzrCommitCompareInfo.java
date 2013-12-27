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
package bazaar4idea.util;

import bazaar4idea.BzrCommit;
import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.changes.Change;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * @author Kirill Likhodedov
 */
public class BzrCommitCompareInfo {
  
  private static final Logger LOG = Logger.getInstance(BzrCommitCompareInfo.class);
  
  private final Map<BzrRepository, Pair<List<BzrCommit>, List<BzrCommit>>> myInfo = new HashMap<BzrRepository, Pair<List<BzrCommit>, List<BzrCommit>>>();
  private final Map<BzrRepository, Collection<Change>> myTotalDiff = new HashMap<BzrRepository, Collection<Change>>();
  private final InfoType myInfoType;

  public BzrCommitCompareInfo() {
    this(InfoType.BOTH);
  }

  public BzrCommitCompareInfo(@NotNull InfoType infoType) {
    myInfoType = infoType;
  }

  public void put(@NotNull BzrRepository repository, @NotNull Pair<List<BzrCommit>, List<BzrCommit>> commits) {
    myInfo.put(repository, commits);
  }

  public void put(@NotNull BzrRepository repository, @NotNull Collection<Change> totalDiff) {
    myTotalDiff.put(repository, totalDiff);
  }

  @NotNull
  public List<BzrCommit> getHeadToBranchCommits(@NotNull BzrRepository repo) {
    return getCompareInfo(repo).getFirst();
  }
  
  @NotNull
  public List<BzrCommit> getBranchToHeadCommits(@NotNull BzrRepository repo) {
    return getCompareInfo(repo).getSecond();
  }

  @NotNull
  private Pair<List<BzrCommit>, List<BzrCommit>> getCompareInfo(@NotNull BzrRepository repo) {
    Pair<List<BzrCommit>, List<BzrCommit>> pair = myInfo.get(repo);
    if (pair == null) {
      LOG.error("Compare info not found for repository " + repo);
      return Pair.create(Collections.<BzrCommit>emptyList(), Collections.<BzrCommit>emptyList());
    }
    return pair;
  }

  @NotNull
  public Collection<BzrRepository> getRepositories() {
    return myInfo.keySet();
  }

  public boolean isEmpty() {
    return myInfo.isEmpty();
  }

  public InfoType getInfoType() {
    return myInfoType;
  }

  @NotNull
  public List<Change> getTotalDiff() {
    List<Change> changes = new ArrayList<Change>();
    for (Collection<Change> changeCollection : myTotalDiff.values()) {
      changes.addAll(changeCollection);
    }
    return changes;
  }

  public enum InfoType {
    BOTH, HEAD_TO_BRANCH, BRANCH_TO_HEAD
  }
}
