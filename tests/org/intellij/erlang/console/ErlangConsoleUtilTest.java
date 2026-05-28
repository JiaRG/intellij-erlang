/*
 * Copyright 2012-2014 Sergey Ignatov
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

package org.intellij.erlang.console;

import junit.framework.TestCase;

public class ErlangConsoleUtilTest extends TestCase {
  public void testDevContainerPathIsConvertedToProcessPath() {
    assertEquals("/workspaces/emqx/apps/emqx_license",
                 ErlangConsoleUtil.getProcessPath("//devcontainer.ij/2b826ebed049@/workspaces/emqx/apps/emqx_license"));
  }

  public void testDevContainerUncPathIsConvertedToProcessPath() {
    assertEquals("/workspaces/emqx/apps/emqx_license",
                 ErlangConsoleUtil.getProcessPath("\\\\devcontainer.ij\\2b826ebed049@\\workspaces\\emqx\\apps\\emqx_license"));
  }

  public void testDevContainerWslPathIsConvertedToProcessPath() {
    assertEquals("/workspaces/emqx/apps/emqx_license",
                 ErlangConsoleUtil.getProcessPath("//devcontainer.ij/2b826ebed049@wsl~Ubuntu/workspaces/emqx/apps/emqx_license"));
  }

  public void testDevContainerWslExecutablePathIsConvertedToProcessPath() {
    assertEquals("/usr/local/lib/erlang/bin/erl",
                 ErlangConsoleUtil.getProcessPath("//devcontainer.ij/2b826ebed049@wsl~Ubuntu/usr/local/lib/erlang/bin/erl"));
  }

  public void testDevContainerWslUncPathIsConvertedToProcessPath() {
    assertEquals("/workspaces/emqx/apps/emqx_license",
                 ErlangConsoleUtil.getProcessPath("\\\\devcontainer.ij\\2b826ebed049@wsl~Ubuntu\\workspaces\\emqx\\apps\\emqx_license"));
  }

  public void testLocalPathIsPreserved() {
    assertEquals("E:/workspace/intellij-erlang",
                 ErlangConsoleUtil.getProcessPath("E:\\workspace\\intellij-erlang"));
  }

  public void testErlangSdkHomeIsInferredFromElixirSdkHome() {
    assertEquals("/usr/local/lib/erlang",
                 ErlangConsoleUtil.inferErlangSdkHomeFromElixirSdkHome("/usr/local/lib/elixir"));
  }
}
