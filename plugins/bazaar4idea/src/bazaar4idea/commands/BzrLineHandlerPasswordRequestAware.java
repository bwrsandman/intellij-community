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
package bazaar4idea.commands;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * {@link BzrLineHandler} that listens to Bazaar output and kills itself if "username" or "password" is queried throughout the prompt.
 * We can't pass the data to the prompt anyway, so better to kill the process and show an error message, than to hang the task.
 * 
 * @author Kirill Likhodedov
 */
public class BzrLineHandlerPasswordRequestAware extends BzrLineHandler {
  
  private boolean myAuthRequest;

  public BzrLineHandlerPasswordRequestAware(@NotNull Project project, @NotNull VirtualFile vcsRoot, @NotNull BzrCommand command) {
    super(project, vcsRoot, command);
  }

  public BzrLineHandlerPasswordRequestAware(@NotNull Project project, @NotNull File directory, @NotNull BzrCommand clone) {
    super(project, directory, clone);
  }

  @Override
  protected void onTextAvailable(String text, Key outputType) {
    super.onTextAvailable(text, outputType);
    if (text.toLowerCase().startsWith("password") || text.toLowerCase().startsWith("username")) {
      myAuthRequest = true;
      destroyProcess();
    }
  }

  public boolean hadAuthRequest() {
    return myAuthRequest;
  }
}
