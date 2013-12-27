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
package bazaar4idea.repo;

import bazaar4idea.BzrStandardRemoteBranch;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsTestUtil;
import bazaar4idea.BzrBranch;
import bazaar4idea.BzrLocalBranch;
import bazaar4idea.BzrRemoteBranch;
import bazaar4idea.test.BzrTestPlatformFacade;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author Kirill Likhodedov
 */
public class BzrConfigTest {
  
  @DataProvider(name = "remote")
  public Object[][] loadRemotes() throws IOException {
    return VcsTestUtil.loadConfigData(getTestDataFolder("remote"));
  }
  
  @DataProvider(name = "branch")
  public Object[][] loadBranches() throws IOException {
    return VcsTestUtil.loadConfigData(getTestDataFolder("branch"));
  }

  private static File getTestDataFolder(String subfolder) {
    File testData = VcsTestUtil.getTestDataFolder();
    return new File(new File(testData, "config"), subfolder);
  }

  @Test(dataProvider = "remote")
  public void testRemotes(String testName, File configFile, File resultFile) throws IOException {
    BzrConfig config = BzrConfig.read(new BzrTestPlatformFacade(), configFile);
    VcsTestUtil.assertEqualCollections(config.parseRemotes(), readRemoteResults(resultFile));
  }
  
  @Test(dataProvider = "branch")
  public void testBranches(String testName, File configFile, File resultFile) throws IOException {
    Collection<BzrBranchTrackInfo> expectedInfos = readBranchResults(resultFile);
    Collection<BzrLocalBranch> localBranches = Collections2.transform(expectedInfos, new Function<BzrBranchTrackInfo, BzrLocalBranch>() {
      @Override
      public BzrLocalBranch apply(@Nullable BzrBranchTrackInfo input) {
        assert input != null;
        return input.getLocalBranch();
      }
    });
    Collection<BzrRemoteBranch> remoteBranches = Collections2.transform(expectedInfos, new Function<BzrBranchTrackInfo, BzrRemoteBranch>() {
      @Override
      public BzrRemoteBranch apply(@Nullable BzrBranchTrackInfo input) {
        assert input != null;
        return input.getRemoteBranch();
      }
    });

    VcsTestUtil.assertEqualCollections(
      BzrConfig.read(new BzrTestPlatformFacade(), configFile).parseTrackInfos(localBranches, remoteBranches),
      expectedInfos);
  }

  private static Collection<BzrBranchTrackInfo> readBranchResults(File file) throws IOException {
    String content = FileUtil.loadFile(file);
    Collection<BzrBranchTrackInfo> remotes = new ArrayList<BzrBranchTrackInfo>();
    String[] remStrings = content.split("BRANCH\n");
    for (String remString : remStrings) {
      if (StringUtil.isEmptyOrSpaces(remString)) {
        continue;
      }
      String[] info = remString.split("\n");
      String branch = info[0];
      BzrRemote remote = getRemote(info[1]);
      String remoteBranchAtRemote = info[2];
      String remoteBranchHere = info[3];
      boolean merge = info[4].equals("merge");
      remotes.add(new BzrBranchTrackInfo(new BzrLocalBranch(branch, BzrBranch.DUMMY_HASH),
                                         new BzrStandardRemoteBranch(remote, remoteBranchAtRemote, BzrBranch.DUMMY_HASH),
                                         merge));
    }
    return remotes;
  }

  private static BzrRemote getRemote(String remoteString) {
    String[] remoteInfo = remoteString.split(" ");
    return new BzrRemote(remoteInfo[0], getSingletonOrEmpty(remoteInfo, 1), getSingletonOrEmpty(remoteInfo, 2),
                         getSingletonOrEmpty(remoteInfo, 3), getSingletonOrEmpty(remoteInfo, 4));
  }

  private static Set<BzrRemote> readRemoteResults(File resultFile) throws IOException {
    String content = FileUtil.loadFile(resultFile);
    Set<BzrRemote> remotes = new HashSet<BzrRemote>();
    String[] remStrings = content.split("REMOTE\n");
    for (String remString : remStrings) {
      if (StringUtil.isEmptyOrSpaces(remString)) {
        continue;
      }
      String[] info = remString.split("\n");
      String name = info[0];
      List<String> urls = getSpaceSeparatedStrings(info[1]);
      Collection<String> pushUrls = getSpaceSeparatedStrings(info[2]);
      List<String> fetchSpec = getSpaceSeparatedStrings(info[3]);
      List<String> pushSpec = getSpaceSeparatedStrings(info[4]);
      BzrRemote remote = new BzrRemote(name, urls, pushUrls, fetchSpec, pushSpec);
      remotes.add(remote);
    }
    return remotes;
  }

  private static List<String> getSpaceSeparatedStrings(String line) {
    if (StringUtil.isEmptyOrSpaces(line)) {
      return Collections.emptyList();
    }
    return Arrays.asList(line.split(" "));
  }

  private static List<String> getSingletonOrEmpty(String[] array, int i) {
    return array.length < i + 1 ? Collections.<String>emptyList() : Collections.singletonList(array[i]);
  }

}
