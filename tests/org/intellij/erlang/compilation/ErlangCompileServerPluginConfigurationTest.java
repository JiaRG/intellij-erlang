/*
 * Copyright 2012-2026 Sergey Ignatov
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

package org.intellij.erlang.compilation;

import com.intellij.openapi.util.io.FileUtil;
import junit.framework.TestCase;

import java.io.File;

public class ErlangCompileServerPluginConfigurationTest extends TestCase {
  public void testCompileServerPluginClasspathMatchesJpsJarArchiveName() throws Exception {
    String jpsBuildScript = FileUtil.loadFile(new File("jps-plugin/build.gradle"));
    String javaDepsXml = FileUtil.loadFile(new File("resources/META-INF/java-deps.xml"));

    assertTrue(jpsBuildScript.contains("jar.archiveFileName = \"jps-plugin.jar\""));
    assertTrue(javaDepsXml.contains("classpath=\"jps-plugin.jar\""));
  }
}
