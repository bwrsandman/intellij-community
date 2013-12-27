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
package bazaar4idea.settings;

import com.intellij.openapi.components.*;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import bazaar4idea.config.UpdateMethod;

/**
 * @author Kirill Likhodedov
 */
@State(name = "Bazaar.Push.Settings", storages = {@Storage(file = StoragePathMacros.WORKSPACE_FILE)})
public class BzrPushSettings implements PersistentStateComponent<BzrPushSettings.State> {

  private State myState = new State();

  public static class State {
    public boolean myUpdateAllRoots = true;
    public UpdateMethod myUpdateMethod = UpdateMethod.MERGE;
  }

  public static BzrPushSettings getInstance(Project project) {
    return ServiceManager.getService(project, BzrPushSettings.class);
  }

  @Override
  public State getState() {
    return myState;
  }

  @Override
  public void loadState(State state) {
    myState = state;
  }

  public boolean shouldUpdateAllRoots() {
    return myState.myUpdateAllRoots;
  }
  
  public void setUpdateAllRoots(boolean updateAllRoots) {
    myState.myUpdateAllRoots = updateAllRoots;
  }

  public UpdateMethod getUpdateMethod() {
    return myState.myUpdateMethod;
  }

  public void setUpdateMethod(UpdateMethod updateMethod) {
    myState.myUpdateMethod = updateMethod;
  }

}
