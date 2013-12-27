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

import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.application.PluginPathManager
import bazaar4idea.BzrLocalBranch
import bazaar4idea.test.BzrLightTest
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

import static bazaar4idea.test.BzrScenarios.commit
import static bazaar4idea.test.BzrScenarios.conflict
import static junit.framework.Assert.assertEquals
import static junit.framework.Assert.assertNull
/**
 * {@link BzrRepositoryReaderTest} reads information from the pre-created .bzr directory from a real project.
 * This one, on the other hand, operates on a live Bazaar repository, putting it to various situations and checking the results.
 *
 * @author Kirill Likhodedov
 */
@Ignore
class BzrRepositoryReaderNewTest extends BzrLightTest {

  BzrRepository myRepository

  @Override
  @Before
  public void setUp() {
    super.setUp();
    myRepository = createRepository(myProjectRoot)
  }


  @Override
  @After
  public void tearDown() {
    super.tearDown();
  }


  @Test
  // inspired by IDEA-93806
  void "rebase with conflicts while being on detached HEAD"() {
    conflict(myRepository, "feature")
    2.times { commit(myRepository) }
    bzr("checkout HEAD^")
    bzr("rebase feature")

    File gitDir = new File(myRepository.getRoot().getPath(), ".bzr")
    def reader = new BzrRepositoryReader(gitDir)
    BzrLocalBranch branch = reader.readCurrentBranch();
    def state = reader.readState();
    assertNull "Current branch can't be identified for this case", branch
    assertEquals "State value is incorrect", Repository.State.REBASING, state
  }

  @Test
  void "test large packed-refs"() {
    File pluginRoot = new File(PluginPathManager.getPluginHomePath("bazaar4idea"));
    File dataDir = new File(new File(pluginRoot, "testData"), "repo");

    File gitDir = new File(myRepository.getRoot().getPath(), ".bzr")
    cd(dataDir.path)
    cp("packed-refs", gitDir)

    def reader = new BzrRepositoryReader(gitDir)
    reader.readBranches(Collections.emptyList())
  }


}
