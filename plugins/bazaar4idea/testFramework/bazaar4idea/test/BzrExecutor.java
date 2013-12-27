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
package bazaar4idea.test;

import bazaar4idea.repo.BzrRepository;
import com.intellij.openapi.vcs.Executor;

import java.util.Arrays;
import java.util.List;

/**
 *
 * @author Kirill Likhodedov
 */
public class BzrExecutor extends Executor {

  private static final String BZR_EXECUTABLE_ENV = "IDEA_TEST_BAZAAR_EXECUTABLE";
  private static final String TEAMCITY_BZR_EXECUTABLE_ENV = "TEAMCITY_BAZAAR_PATH";

  private static final int MAX_RETRIES = 3;
  private static boolean myVersionPrinted;
  public static final String Bzr_EXECUTABLE = findBzrExecutable();

  private static String findBzrExecutable() {
    return findExecutable("Bazaar", "bzr", "bzr.exe", Arrays.asList(BZR_EXECUTABLE_ENV, TEAMCITY_BZR_EXECUTABLE_ENV));
  }

  public static String bzr(String command) {
    printVersionTheFirstTime();
    List<String> split = splitCommandInParameters(command);
    split.add(0, Bzr_EXECUTABLE);
    log("bzr " + command);
    for (int attempt = 0; attempt < 3; attempt++) {
      String stdout = run(split);
      if (stdout.contains("fatal") && stdout.contains("Unable to create") && stdout.contains(".bzr/index.lock")) {
        if (attempt > MAX_RETRIES) {
          throw new RuntimeException("fatal error during execution of Bazaar command: $command");
        }
      }
      else {
        return stdout;
      }
    }
    throw new RuntimeException("fatal error during execution of Bazaar command: $command");
  }


  public static String bzr(BzrRepository repository, String command) {
    if (repository != null) {
      cd(repository);
    }
    return bzr(command);
  }

  public static String bzr(String formatString, String... args) {
    return bzr(String.format(formatString, args));
  }

  public static void cd(BzrRepository repository) {
    cd(repository.getRoot().getPath());
  }

  private static void printVersionTheFirstTime() {
    if (!myVersionPrinted) {
      myVersionPrinted = true;
      bzr("version");
    }
  }

}
