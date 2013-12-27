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
package bazaar4idea.test

import com.intellij.dvcs.test.MockProject
import com.intellij.dvcs.test.MockVirtualFile
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import bazaar4idea.BzrPlatformFacade
import bazaar4idea.commands.Bzr
import bazaar4idea.repo.BzrRepository
import bazaar4idea.repo.BzrRepositoryImpl
import org.jetbrains.annotations.NotNull
import org.junit.After
import org.junit.Before
/**
 * <p>BzrLightTest is a test that doesn't need to start the whole {@link com.intellij.openapi.application.Application} and Project.
 *    It substitutes everything with Mocks, and communicates with this mocked platform via {@link com.intellij.dvcs.test.DvcsTestPlatformFacade}.</p>
 *
 * <p>However, BzrLightTests tests are not entirely unit. They may use other components from the bazaar4idea plugin, they operate on the
 *    real file system, and they call native Bazaar to prepare test case and from the code which is being tested.</p>
 *
 * @author Kirill Likhodedov
 */
class BzrLightTest extends BzrExecutor {

  /**
   * The file system root of test files.
   * Automatically deleted on {@link #tearDown()}.
   * Tests should create new files only inside this directory.
   */
  public String myTestRoot

  /**
   * The file system root of the project. All project should locate inside this directory.
   */
  public String myProjectRoot

  public MockProject myProject
  public BzrPlatformFacade myPlatformFacade
  public Bzr myBzr

  @Before
  public void setUp() {
    myTestRoot = FileUtil.createTempDirectory("", "").getPath()
    cd myTestRoot
    myProjectRoot = mkdir ("project")
    myProject = new MockProject(myProjectRoot)
    myPlatformFacade = new BzrTestPlatformFacade()
    myBzr = new BzrTestImpl()
  }

  @After
  protected void tearDown() {
    FileUtil.delete(new File(myTestRoot))
    Disposer.dispose(myProject)
  }

  public BzrRepository createRepository(String rootDir) {
    BzrTestUtil.initRepo(rootDir);

    // TODO this smells hacky
    // the constructor and notifyListeners() should probably be private
    // getPresentableUrl should probably be final, and we should have a better VirtualFile implementation for tests.
    BzrRepository repository = new BzrRepositoryImpl(new MockVirtualFile(rootDir), myPlatformFacade, myProject, myProject, true) {
      @NotNull
      @Override
      public String getPresentableUrl() {
        return rootDir;
      }

    };

    ((BzrTestRepositoryManager)myPlatformFacade.getRepositoryManager(myProject)).add(repository);
    return repository;
  }

  /**
   * Clones the given source repository into a bare parent.bzr and adds the remote origin.
   */
  protected void prepareRemoteRepo(BzrRepository source, String target = "parent.bzr", String targetName = "origin") {
    cd myTestRoot
    bzr("clone --bare $source $target")
    cd source
    bzr("remote add $targetName $myTestRoot/$target");
  }
}
