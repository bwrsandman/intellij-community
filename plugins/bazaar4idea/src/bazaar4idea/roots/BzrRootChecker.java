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
package bazaar4idea.roots;

import bazaar4idea.BzrUtil;
import bazaar4idea.BzrVcs;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vcs.VcsRootChecker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * @author Kirill Likhodedov
 */
public class BzrRootChecker extends VcsRootChecker {

  @Override
  public boolean isRoot(@NotNull String path) {
    return new File(path, BzrUtil.DOT_BZR).exists();
  }

  @Override
  @NotNull
  public VcsKey getSupportedVcs() {
    return BzrVcs.getKey();
  }

  @Override
  public boolean isVcsDir(@Nullable String path) {
    return path != null && path.toLowerCase().endsWith(BzrUtil.DOT_BZR);
  }
}
