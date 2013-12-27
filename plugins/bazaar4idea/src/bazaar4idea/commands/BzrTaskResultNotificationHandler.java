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

import bazaar4idea.BzrVcs;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

/**
 * @author Kirill Likhodedov
 */
public class BzrTaskResultNotificationHandler extends BzrTaskResultHandlerAdapter {
  private final Project myProject;
  private final String mySuccessMessage;
  private final String myCancelMessage;
  private final String myErrorMessage;

  public BzrTaskResultNotificationHandler(Project project, String successMessage, String cancelMessage, String errorMessage) {
    myProject = project;
    mySuccessMessage = successMessage;
    myCancelMessage = cancelMessage;
    myErrorMessage = errorMessage;
  }

  @Override protected void onSuccess() {
    BzrVcs.NOTIFICATION_GROUP_ID.createNotification(mySuccessMessage, NotificationType.INFORMATION).notify(myProject);
  }

  @Override protected void onCancel() {
    BzrVcs.NOTIFICATION_GROUP_ID.createNotification(myCancelMessage, NotificationType.INFORMATION).notify(myProject);
  }

  @Override protected void onFailure() {
    BzrVcs.IMPORTANT_ERROR_NOTIFICATION.createNotification(myErrorMessage, NotificationType.ERROR).notify(myProject);
  }
}
