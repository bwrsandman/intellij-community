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
package bazaar4idea.commands;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import bazaar4idea.http.BzrAskPassApp;
import bazaar4idea.http.BzrAskPassXmlRpcHandler;
import bazaar4idea.ssh.BzrXmlRpcHandlerService;
import bazaar4idea.util.ScriptGenerator;

/**
 * Provides the authentication mechanism for Bazaar HTTP connections.
 */
public abstract class BzrHttpAuthService extends BzrXmlRpcHandlerService<BzrHttpAuthenticator> {

  protected BzrHttpAuthService() {
    super("bzr-askpass-", BzrAskPassXmlRpcHandler.HANDLER_NAME, BzrAskPassApp.class);
  }

  @Override
  protected void customizeScriptGenerator(@NotNull ScriptGenerator generator) {
  }

  @NotNull
  @Override
  protected Object createRpcRequestHandlerDelegate() {
    return new InternalRequestHandlerDelegate();
  }

  /**
   * Creates new {@link BzrHttpAuthenticator} that will be requested to handle username and password requests from Bazaar.
   */
  @NotNull
  public abstract BzrHttpAuthenticator createAuthenticator(@NotNull Project project, @Nullable ModalityState state,
                                                           @NotNull BzrCommand command, @NotNull String url);

  /**
   * Internal handler implementation class, it is made public to be accessible via XML RPC.
   */
  public class InternalRequestHandlerDelegate implements BzrAskPassXmlRpcHandler {
    @NotNull
    @Override
    public String askUsername(int handler, @NotNull String url) {
      return getHandler(handler).askUsername(url);
    }

    @NotNull
    @Override
    public String askPassword(int handler, @NotNull String url) {
      return getHandler(handler).askPassword(url);
    }
  }

}
