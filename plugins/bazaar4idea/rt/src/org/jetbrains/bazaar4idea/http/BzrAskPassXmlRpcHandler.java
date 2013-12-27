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
package org.jetbrains.bazaar4idea.http;

import org.jetbrains.annotations.NotNull;

/**
 * This handler is called via XML RPC from {@link BzrAskPassApp} when Bazaar requests user credentials.
 *
 * @author Kirill Likhodedov
 */
public interface BzrAskPassXmlRpcHandler {

  String BAZAAR_ASK_PASS_ENV = "BAZAAR_ASKPASS";
  String BAZAAR_ASK_PASS_HANDLER_ENV = "BAZAAR_ASKPASS_HANDLER";
  String BAZAAR_ASK_PASS_PORT_ENV = "BAZAAR_ASKPASS_PORT";
  String HANDLER_NAME = BzrAskPassXmlRpcHandler.class.getName();

  /**
   * Get the username from the user to access the given URL.
   * @param handler XML RPC handler number.
   * @param url     URL which Bazaar tries to access.
   * @return The Username which should be used for the URL.
   */
  // UnusedDeclaration suppressed: the method is used via XML RPC
  @SuppressWarnings("UnusedDeclaration")
  @NotNull
  String askUsername(int handler, @NotNull String url);

  /**
   * Get the password from the user to access the given URL.
   * It is assumed that the username either is specified in the URL (http://username@host.com), or has been asked earlier.
   * @param handler XML RPC handler number.
   * @param url     URL which Bazaar tries to access.
   * @return The password which should be used for the URL.
   */
  // UnusedDeclaration suppressed: the method is used via XML RPC
  @SuppressWarnings("UnusedDeclaration")
  @NotNull
  String askPassword(int handler, @NotNull String url);

}
