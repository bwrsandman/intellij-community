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
package bazaar4idea.config;

import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The application wide settings for the Bazaar
 */
@State(
  name = "Bazaar.Application.Settings",
  storages = {@Storage(file = StoragePathMacros.APP_CONFIG + "/vcs.xml")})
public class BzrVcsApplicationSettings implements PersistentStateComponent<BzrVcsApplicationSettings.State> {


  private State myState = new State();

  /**
   * Kinds of SSH executable to be used with the git
   */
  public enum SshExecutable {
    IDEA_SSH,
    NATIVE_SSH,
  }

  public static class State {
    public String myPathToBzr = null;
    public SshExecutable SSH_EXECUTABLE = null;
  }

  public static BzrVcsApplicationSettings getInstance() {
    return ServiceManager.getService(BzrVcsApplicationSettings.class);
  }

  @Override
  public State getState() {
    return myState;
  }

  public void loadState(State state) {
    myState = state;
  }

  @NotNull
  public String getPathToBzr() {
    if (myState.myPathToBzr == null) {
      // detecting right away, this can be called from the default project without a call to BzrVcs#activate()
      myState.myPathToBzr = new BzrExecutableDetector().detect();
    }
    return myState.myPathToBzr;
  }

  public void setPathToBzr(String pathToBzr) {
    myState.myPathToBzr = pathToBzr;
  }

  public void setIdeaSsh(@NotNull SshExecutable executable) {
    myState.SSH_EXECUTABLE = executable;
  }

  @Nullable
  SshExecutable getIdeaSsh() {
    return myState.SSH_EXECUTABLE;
  }

}
