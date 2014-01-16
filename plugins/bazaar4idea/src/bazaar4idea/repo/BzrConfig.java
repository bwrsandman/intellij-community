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

import bazaar4idea.BzrPlatformFacade;
import bazaar4idea.BzrUtil;
import com.intellij.dvcs.repo.RepoStateException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>Reads information from the {@code .git/config} file, and parses it to actual objects.</p>
 *
 * <p>Currently doesn't read all the information: general information about remotes and branch tracking</p>
 *
 * <p>Parsing is performed with the help of <a href="http://ini4j.sourceforge.net/">ini4j</a> library.</p>
 *
 * @author Kirill Likhodedov
 */
public class BzrConfig {

  private static final Logger LOG = Logger.getInstance(BzrConfig.class);

  private static final Pattern REMOTE_SECTION = Pattern.compile("(\\S*)\\s*=\\s*(\\S*)");
  private static final Pattern URL_SECTION = Pattern.compile("url \"(.*)\"");

  @NotNull private final Collection<Remote> myRemotes;
  @NotNull private final Collection<Url> myUrls;


  private BzrConfig(@NotNull Collection<Remote> remotes, @NotNull Collection<Url> urls) {
    myRemotes = remotes;
    myUrls = urls;
  }

  /**
   * <p>Returns Bazaar remotes defined in {@code .bzr/branch/branch.conf}.</p>
   *
   * @return Bazaar remotes defined in {@code .bzr/branch/branch.conf}.
   */
  @NotNull
  Collection<BzrRemote> parseRemotes() {
    // populate BzrRemotes with substituting urls when needed
    return ContainerUtil.map(myRemotes, new Function<Remote, BzrRemote>() {
      @Override
      public BzrRemote fun(@Nullable Remote remote) {
        assert remote != null;
        return convertRemoteToBzrRemote(myUrls, remote);
      }
    });
  }

  @NotNull
  private static BzrRemote convertRemoteToBzrRemote(@NotNull Collection<Url> urls, @NotNull Remote remote) {
    UrlsAndPushUrls substitutedUrls = substituteUrls(urls, remote);
    return new BzrRemote(remote.myName, substitutedUrls.getUrls(), substitutedUrls.getPushUrls(),
                         null, null);
  }

  /**
   * Creates an instance of BzrConfig by reading information from the specified {@code .git/config} file.
   * @throws RepoStateException if {@code .git/config} couldn't be read or has invalid format.<br/>
   *         If in general it has valid format, but some sections are invalid, it skips invalid sections, but reports an error.
   */
  @NotNull
  static BzrConfig read(@NotNull BzrPlatformFacade platformFacade, @NotNull File configFile) {

    // TODO read conf from .bzr/branch/branches.conf

    Pair<Collection<Remote>, Collection<Url>> remotesAndUrls = parseRemotes(configFile);

    return new BzrConfig(remotesAndUrls.getFirst(), remotesAndUrls.getSecond());
  }

  @NotNull
  private static Pair<Collection<Remote>, Collection<Url>> parseRemotes(@NotNull File configFile) {
    Collection<Remote> remotes = new ArrayList<Remote>();
    Collection<Url> urls = new ArrayList<Url>();
    try {
      BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(configFile), BzrUtil.UTF8_CHARSET));
      String line;
      while ((line = in.readLine()) != null) {
        Matcher match = REMOTE_SECTION.matcher(line);
        if (match.matches()) {
          remotes.add(new Remote(match.group(1), match.group(2)));
        }
      }
      in.close();
    } catch (IOException e) {
      LOG.error(e.getMessage());
    }
    return Pair.create(remotes, urls);
  }

  @NotNull
  private static UrlsAndPushUrls substituteUrls(@NotNull Collection<Url> urlSections, @NotNull Remote remote) {
    List<String> urls = new ArrayList<String>();
    Collection<String> pushUrls = new ArrayList<String>();

    urls.add(remote.getUrl());
    pushUrls.add(remote.getUrl());

    return new UrlsAndPushUrls(urls, pushUrls);
  }

  private static class UrlsAndPushUrls {
    final List<String> myUrls;
    final Collection<String> myPushUrls;

    private UrlsAndPushUrls(List<String> urls, Collection<String> pushUrls) {
      myPushUrls = pushUrls;
      myUrls = urls;
    }

    public Collection<String> getPushUrls() {
      return myPushUrls;
    }

    public List<String> getUrls() {
      return myUrls;
    }
  }

  private static class Remote {

    private final String myName;
    private final String myRemoteUrl;

    private Remote(@NotNull String name, @NotNull String remoteUrl) {
      myRemoteUrl = remoteUrl;
      myName = name;
    }

    @NotNull
    private String getUrl() {
      return myRemoteUrl;
    }

    @NotNull
    private String getPushUrl() {
      return getUrl();
    }

  }

  private static class Url {
    private final String myName;
    private final UrlBean myUrlBean;

    private Url(String name, UrlBean urlBean) {
      myUrlBean = urlBean;
      myName = name;
    }

    @Nullable
    // null means to entry, i.e. nothing to substitute. Empty string means substituting everything
    public String getInsteadOf() {
      return myUrlBean.getInsteadOf();
    }

  }

  private interface UrlBean {
    @Nullable String getInsteadOf();
    @Nullable String getPushInsteadOf();
  }

}
