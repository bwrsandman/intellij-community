/*
 * Copyright 2000-2010 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bazaar4idea.history.wholeTree;

import bazaar4idea.BzrRevisionNumber;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import bazaar4idea.BzrContentRevision;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author irengrig
 */
public class BzrMultipleContentsRevision implements ContentRevision {
  private final FilePath myPath;
  private final List<BzrRevisionNumber> myList;
  protected ContentRevision myRevision;

  public BzrMultipleContentsRevision(final FilePath path, final List<BzrRevisionNumber> list, final BzrContentRevision contentRevision) throws VcsException {
    myPath = path;
    myList = list;
    myRevision = contentRevision;
  }

  @Override
  public String getContent() throws VcsException {
    return myRevision.getContent();
  }

  @NotNull
  @Override
  public FilePath getFile() {
    return myPath;
  }

  @NotNull
  @Override
  public VcsRevisionNumber getRevisionNumber() {
    return myRevision.getRevisionNumber();
  }

  public List<BzrRevisionNumber> getList() {
    return myList;
  }
}
