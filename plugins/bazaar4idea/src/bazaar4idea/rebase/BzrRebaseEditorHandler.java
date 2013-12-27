/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package bazaar4idea.rebase;

import bazaar4idea.commands.BzrHandler;

/**
 * The interface
 */
public interface BzrRebaseEditorHandler {
  /**
   * Edit commits request
   *
   * @param path the path to editing
   * @return the exit code to be returned from editor
   */
  int editCommits(String path);

  /**
   * @return the handler for the git process
   */
  BzrHandler getHandler();
}
