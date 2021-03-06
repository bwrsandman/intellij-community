/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package bazaar4idea;

import bazaar4idea.repo.BzrRepository;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.VfsTestUtil;
import cucumber.annotation.en.Given;
import cucumber.annotation.en.Then;
import cucumber.annotation.en.When;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static bazaar4idea.BzrCucumberWorld.*;
import static bazaar4idea.test.BzrExecutor.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Kirill Likhodedov
 */
public class BzrAddSteps {

  @Given("^unversioned file (.*)$")
  public void unversioned_file(String filePath) throws Throwable {
    cd(myRepository);
    VfsTestUtil.createFile(myProjectDir, filePath);
  }

  @When("^I add (.*) to VCS$")
  public void I_add_file_to_VCS(List<String> files) throws Throwable {
    List<VirtualFile> vFiles = Lists.transform(files, new Function<String, VirtualFile>() {
      @Override
      public VirtualFile apply(@Nullable String file) {
        assertNotNull(file);
        if ("the project dir".equals(file)) {
          return myRepository.getRoot();
        }
        return myRepository.getRoot().findFileByRelativePath(file);
      }
    });
    final CountDownLatch latch = new CountDownLatch(1);
    //executeOnPooledThread(new Runnable() {
    //  public void run() {
    //    myChangeListManager.ensureUpToDate(false);
    //    latch.countDown();
    //  }
    //});
    latch.await(5, TimeUnit.SECONDS);
    myChangeListManager.addUnversionedFiles(myChangeListManager.addChangeList("dummy", null), vFiles);
  }


  @Then("^(.*) should become ADDED$")
  public void file_should_become_ADDED(String filePath) throws Throwable {
    VirtualFile vf = myProjectDir.findFileByRelativePath(filePath);
    assertNotNull(vf);
    BzrRepository repo = myPlatformFacade.getRepositoryManager(myProject).getRepositoryForFile(vf);
    String status = bzr(repo, "status --short " + vf.getPath());
    assertTrue("File status is not added: " + status, 'A' == status.charAt(0));
  }
}
