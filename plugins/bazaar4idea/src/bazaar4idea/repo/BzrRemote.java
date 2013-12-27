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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 *   Holds information about a remote in Bazaar repository.
 * </p>
 *
 * <p>
 *   Bazaar remote as defined in {@code .git/config} may contain url(s) and pushUrl(s). <br/>
 *   If no pushUrl is given, then url is used to fetch and to push. Otherwise url is used to fetch, pushUrl is used to push.
 *   If there are several urls and no pushUrls, then 1 url is used to fetch (because it is not possible and makes no sense to fetch
 *   several urls at once), and all urls are used to push. If there are several urls and at least one pushUrl, then only pushUrl(s)
 *   are used to push.
 *   There are also some rules about url substitution, like {@code url.<base>.insteadOf}.
 * </p>
 * <p>
 *   BzrRemote instance constructed by {@link BzrConfig#read(bazaar4idea.BzrPlatformFacade, File)}} has all these rules applied.
 *   Thus, for example, if only one {@code url} and no {@code pushUrls} are defined for the remote, 
 *   both {@link #getUrls()} and {@link #getPushUrls()} will return this url. <br/>
 *   This is made to avoid urls transformation logic from the code using BzrRemote, leaving it all in BzrConfig parsing.
 * </p>
 * <p>
 *   Same applies to fetch and push specs: {@link #getPushRefSpecs()} returns the spec,
 *   even if there are no separate record in {@code .git/config}
 * </p>
 * 
 * <p>
 *   NB: Not all remote preferences (defined in {@code .git/config} are stored in the object.
 *   If some additional data is needed, add the field, getter, constructor parameter and populate it in {@link BzrConfig}.
 * </p>
 * 
 * <p>Remotes are compared (via equals, hashcode and compareTo) only by names.</p>
 * 
 * @author Kirill Likhodedov
 */
public final class BzrRemote implements Comparable<BzrRemote> {

  /**
   * This is a special instance of BzrRemote used in typical git-svn configurations like:
   * [branch "trunk"]
   *   remote = .
   *   merge = refs/remotes/git-svn
   */
  public static final BzrRemote DOT = new BzrRemote(".", Collections.singletonList("."), Collections.<String>emptyList(),
                                                         Collections.<String>emptyList(), Collections.<String>emptyList());

  /**
   * Default remote name in Bazaar is ":parent".
   * Usually all Bazaar repositories have a ":parent" remote, so it can be used as a default value in some cases.
   */
  public static final String ORIGIN_NAME = ":parent";

  @NotNull private final String myName;
  @NotNull private final List<String> myUrls;
  @NotNull private final Collection<String> myPushUrls;
  @NotNull  final List<String> myFetchRefSpecs;
  @NotNull private final List<String> myPushRefSpecs;

  BzrRemote(@NotNull String name,
            @NotNull List<String> urls,
            @NotNull Collection<String> pushUrls,
            @NotNull List<String> fetchRefSpecs,
            @NotNull List<String> pushRefSpecs) {
    myName = name;
    myUrls = urls;
    myPushUrls = pushUrls;
    myFetchRefSpecs = fetchRefSpecs;
    myPushRefSpecs = pushRefSpecs;
  }

  @NotNull
  public String getName() {
    return myName;
  }

  /**
   * Returns all urls specified in gitconfig in {@code remote.<name>.url}.
   * If you need url to fetch, use {@link #getFirstUrl()}, because only the first url is fetched by Bazaar,
   * others are ignored.
   */
  @NotNull
  public List<String> getUrls() {
    return myUrls;
  }

  /**
   * @return the first url (to fetch) or null if and only if there are no urls defined for the remote.
   */
  @Nullable
  public String getFirstUrl() {
    return myUrls.isEmpty() ? null : myUrls.get(0);
  }

  @NotNull
  public Collection<String> getPushUrls() {
    return myPushUrls;
  }

  @NotNull
  public List<String> getFetchRefSpecs() {
    return myFetchRefSpecs;
  }

  @NotNull
  public List<String> getPushRefSpecs() {
    return myPushRefSpecs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    BzrRemote gitRemote = (BzrRemote)o;
    return myName.equals(gitRemote.myName);

    // other parameters don't count: remotes are equal if their names are equal
    // TODO: LOG.warn if other parameters differ
  }

  @Override
  public int hashCode() {
    return myName.hashCode();
  }

  @Override
  public String toString() {
    return String.format("BzrRemote{myName='%s', myUrls=%s, myPushUrls=%s, myFetchRefSpec='%s', myPushRefSpec='%s'}",
                         myName, myUrls, myPushUrls, myFetchRefSpecs, myPushRefSpecs);
  }

  @Override
  public int compareTo(@NotNull BzrRemote o) {
    return getName().compareTo(o.getName());
  }

}
