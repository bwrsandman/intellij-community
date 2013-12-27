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
package bazaar4idea.history;

import bazaar4idea.BzrFormatException;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kirill Likhodedov
 */
enum BzrChangeType {
  MODIFIED('M'),
  ADDED('A'),
  COPIED('C'),
  DELETED('D'),
  RENAMED('R'),
  UNRESOLVED('U'),
  TYPE_CHANGED('T')
  ;

  private final char myChar;

  BzrChangeType(char c) {
    myChar = c;
  }

  /**
   * Finds the BzrChangeType by the given string returned by Bazaar.
   * @throws bazaar4idea.BzrFormatException if such status can't be found: it means either a developer mistake missing a possible valid status,
   * or a Bazaar invalid output.
   */
  @NotNull
  static BzrChangeType fromString(@NotNull String statusString) {
    assert statusString.length() > 0;
    char c = statusString.charAt(0);
    for (BzrChangeType changeType : values()) {
      if (changeType.myChar == c) {
        return changeType;
      }
    }
    throw new BzrFormatException("Unexpected status [" + statusString + "]");
  }

  @Override
  public String toString() {
    return String.valueOf(Character.toUpperCase(myChar));
  }
  
}
