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
package bazaar4idea.history.browser;

import bazaar4idea.BzrBranch;
import bazaar4idea.history.wholeTree.AbstractHash;
import org.jetbrains.annotations.Nullable;

/**
 * Created by IntelliJ IDEA.
 * User: Irina.Chernushina
 * Date: 12/1/11
 * Time: 10:18 PM
 * To change this template use File | Settings | File Templates.
 */
public interface SymbolicRefsI {
  /*TreeSet<String> getLocalBranches();

  TreeSet<String> getRemoteBranches();*/

  @Nullable
  String getCurrentName();

  BzrBranch getCurrent();

  SymbolicRefs.Kind getKind(String s);

  String getTrackedRemoteName();

  String getUsername();

  AbstractHash getHeadHash();
}
