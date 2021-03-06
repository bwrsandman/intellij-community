/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;

/**
 * <p>
 * This enum stores the collection of bugs and features of different versions of Bazaar.
 * To check if the bug exists in current version call {@link #existsIn(BzrVersion)}.
 * </p>
 * @author Kirill Likhodedov
 */
public enum BzrVersionSpecialty {

  NEEDS_QUOTES_IN_STASH_NAME {
    @Override
    public boolean existsIn(@NotNull BzrVersion version) {
      return version.getType().equals(BzrVersion.Type.CYGWIN);
    }
  };

  public abstract boolean existsIn(@NotNull BzrVersion version);

}
