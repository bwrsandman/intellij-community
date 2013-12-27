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
package bazaar4idea.config

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.annotations.NotNull
import org.junit.Before
import org.junit.Test

import java.lang.reflect.Field
import java.lang.reflect.Modifier

import static junit.framework.Assert.assertEquals
import static org.junit.Assume.assumeTrue

/**
 *
 * @author Kirill Likhodedov
 */
class BzrExecutableDetectorWindowsTest {

  private File testRoot
  private def CAN_RUN = []
  private String PATH = "%SystemRoot%/system32;%SystemRoot%;%SystemRoot%/System32/Wbem;%SYSTEMROOT%/System32/WindowsPowerShell/v1.0/;"

  @Before
  void setUp() {
    assumeTrue(SystemInfo.isWindows)
    testRoot = FileUtil.createTempDirectory("", "")
    setWindowsRoot(new File(testRoot, "C_"));
  }

  @Test
  void "Simple case"() {
    fs "C:/Program Files/Bazaar/bin/bzr.exe"
    assertExecutable "C:/Program Files/Bazaar/bin/bzr.exe"
  }

  @Test
  void "Prefer default Bazaar without version to versioned ones"() {
    fs "C:/Program Files/Bazaar/bin/bzr.exe",
       "C:/Program Files/Bazaar 1.8/bin/bzr.exe",
       "C:/Program Files/Bazaar_1.7.1/bin/bzr.exe"
    assertExecutable "C:/Program Files/Bazaar/bin/bzr.exe"
  }

  @Test
  void "Prefer the latest version"() {
    fs "C:/Program Files (x86)/Bazaar 1.8/cmd/bzr.exe",
       "C:/Program Files/Bazaar_1.7.1/bin/bzr.exe",
       "C:/Program Files/Bazaar_1.7.5/cmd/bzr.cmd",
       "C:/Program Files (x86)/Bazaar_1.7.0.2/bin/bzr.exe"
    assertExecutable "C:/Program Files (x86)/Bazaar 1.8/cmd/bzr.exe"
  }

  @Test
  void "Prefer Program Files over x86"() {
    fs "C:/Program Files (x86)/Bazaar 1.8/bin/bzr.exe",
       "C:/Program Files/Bazaar 1.8/bin/bzr.exe"
    assertExecutable "C:/Program Files/Bazaar 1.8/bin/bzr.exe"
  }

  @Test
  void "Prefer git_cmd over git_exe"() {
    fs "C:/Program Files (x86)/Bazaar 1.7.4/bin/bzr.exe",
       "C:/Program Files/Bazaar 1.7.4/cmd/bzr.cmd"
    assertExecutable "C:/Program Files/Bazaar 1.7.4/cmd/bzr.cmd"
  }

  @Test
  void "Prefer cmd over bin in newer versions of Bazaar"() {
    fs "C:/Program Files (x86)/Bazaar 1.8/bin/bzr.exe",
       "C:/Program Files/Bazaar 1.8/cmd/bzr.exe"
    assertExecutable "C:/Program Files/Bazaar 1.8/cmd/bzr.exe"
  }

  @Test
  void "1_8_0 Prefer cmd_git_cmd over cmd_git_exe and bin_git_exe"() {
    fs "C:/Program Files (x86)/Bazaar_1.8/bin/bzr.exe",
       "C:/Program Files (x86)/Bazaar_1.8/cmd/bzr.cmd",
       "C:/Program Files (x86)/Bazaar_1.8/cmd/bzr.exe"
    assertExecutable "C:/Program Files (x86)/Bazaar_1.8/cmd/bzr.cmd"
  }

  @Test
  void "Prefer msys over cygwin"() {
    fs "C:/Program Files (x86)/Bazaar_1.8.0.2/cmd/bzr.exe",
       "C:/Program Files (x86)/Bazaar_1.8.0.2/bin/bzr.exe",
       "C:/cygwin/bin/bzr.exe"
    assertExecutable "C:/Program Files (x86)/Bazaar_1.8.0.2/cmd/bzr.exe"
  }

  @Test
  void "Only cygwin"() {
    fs "C:/cygwin/bin/bzr.exe"
    assertExecutable "C:/cygwin/bin/bzr.exe"
  }

  @Test
  void "Many different versions, real case"() {
    fs "C:/Program Files (x86)/Bazaar_1.7.0.2/bin/bzr.exe",
       "C:/Program Files (x86)/Bazaar_1.7.0.2/cmd/bzr.cmd",
       "C:/Program Files (x86)/Bazaar_1.7.8/bin/bzr.exe",
       "C:/Program Files (x86)/Bazaar_1.7.8/cmd/bzr.cmd",
       "C:/Program Files (x86)/Bazaar_1.8/bin/bzr.exe",
       "C:/Program Files (x86)/Bazaar_1.8/cmd/bzr.cmd",
       "C:/Program Files (x86)/Bazaar_1.8/cmd/bzr.exe",
       "C:/Program Files (x86)/Bazaar_1.8.0.2/cmd/bzr.exe",
       "C:/Program Files (x86)/Bazaar_1.8.0.2/bin/bzr.exe",
       "C:/cygwin/bin/bzr.exe"
    assertExecutable "C:/Program Files (x86)/Bazaar_1.8.0.2/cmd/bzr.exe"
  }

  @Test
  void "Program not found try git_exe"() {
    CAN_RUN = [ "bzr.exe" ]
    assertExecutable "bzr.exe"
  }

  @Test
  void "For both git_exe and git_cmd prefer git_cmd"() {
    CAN_RUN = [ "bzr.exe", "bzr.cmd" ]
    assertExecutable "bzr.cmd"
  }

  @Test
  void "Find Bazaar in PATH"() {
    PATH = "D:/Program Files (x86)/Bazaar_distr/cmd";
    fs "D:/Program Files (x86)/Bazaar_distr/cmd/bzr.cmd"
    assertExecutable "D:/Program Files (x86)/Bazaar_distr/cmd/bzr.cmd"
  }

  @Test
  void "Find Bazaar in PATH not on the first place"() {
    PATH = "C:/Ruby193/bin;C:/Users/John.Doe/Documents/Bazaar_1.8.0.2/bin";
    fs "C:/Users/John.Doe/Documents/Bazaar_1.8.0.2/bin/bzr.exe"
    assertExecutable "C:/Users/John.Doe/Documents/Bazaar_1.8.0.2/bin/bzr.exe"
  }

  @Test
  void "No Bazaar in PATH, then look in Program Files"() {
    PATH = "%SystemRoot%/system32;%SystemRoot%;%SystemRoot%/System32/Wbem;%SYSTEMROOT%/System32/WindowsPowerShell/v1.0/;" +
           "C:/Program Files/Intel/DMIX;C:/Program Files/Mercurial/;C:/Program Files/TortoiseHg/;"
    fs "C:/Program Files/Bazaar/bin/bzr.exe"
    assertExecutable "C:/Program Files/Bazaar/bin/bzr.exe"
  }

  @Test
  void "Prefer PATH to Program Files"() {
    PATH = "C:/Ruby193/bin;C:/Users/John.Doe/Documents/Bazaar_1.8.0.2/bin";
    fs "C:/Program Files/Bazaar/bin/bzr.exe",
       "C:/Users/John.Doe/Documents/Bazaar_1.8.0.2/bin/bzr.exe"
    assertExecutable "C:/Users/John.Doe/Documents/Bazaar_1.8.0.2/bin/bzr.exe"
  }

  @Test
  void "Prefer the first entry from the PATH"() {
    PATH = "C:/Ruby193/bin;D:/Bazaar/cmd;C:/Users/John.Doe/Documents/Bazaar_1.8.0.2/bin;";
    fs "D:/Bazaar/cmd/bzr.cmd",
       "C:/Users/John.Doe/Documents/Bazaar_1.8.0.2/bin/bzr.exe"
    assertExecutable "D:/Bazaar/cmd/bzr.cmd"
  }

  @Test
  void "Don't use Bazaar from PATH if it doesn't exist on disk"() {
    PATH = "C:/Ruby193/bin;D:/Bazaar/cmd;";
    fs "C:/Program Files/Bazaar/bin/bzr.exe"
    assertExecutable "C:/Program Files/Bazaar/bin/bzr.exe"
  }

  def assertExecutable(String expected) {
    // we want to specify unix-like paths in "expected", because they are easier to write :)
    def detected = detect()
    detected = convertBack(detected)
    assertEquals "Incorrect executable detected", expected, detected
  }

  String convertBack(String detected) {
    detected = detected.replace(testRoot.path + File.separator, "").replace('\\', '/')
    detected = returnDiskColor(detected)
    return detected
  }

  def fs(String... paths) {
    paths.each {
      mkPath(it)
    }
  }

  def mkPath(String path) {
    File file = new File(convertPath(path))
    file.getParentFile().mkdirs()
    file.createNewFile()
  }

  private String convertPath(String path) {
    path = FileUtil.toSystemDependentName(replaceDiskColon(path))
    return testRoot.getPath() + File.separator + path
  }

  static String replaceDiskColon(String path) {
    if (path[1] == ':') {
      return path[0] + "_" + path.substring(2)
    }
    return path
  }

  private static String returnDiskColor(String path) {
    if (path[1] == '_') {
      return path[0] + ":" + path.substring(2)
    }
    return path
  }

  private String detect() {
    new BzrExecutableDetector() {
      @Override
      protected boolean runs(@NotNull String exec) {
        return CAN_RUN.contains(exec);
      }

      @Override
      protected String getPath() {
        return PATH.split(";").collect { convertPath(it) }.join(";");
      }
    }.detect();
  }

  static def setWindowsRoot(File file) {
    Field field = BzrExecutableDetector.class.getDeclaredField("WIN_ROOT");
    field.setAccessible(true);

    Field modifiersField = Field.class.getDeclaredField("modifiers");
    modifiersField.setAccessible(true);
    modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

    field.set(null, file);
  }

}
