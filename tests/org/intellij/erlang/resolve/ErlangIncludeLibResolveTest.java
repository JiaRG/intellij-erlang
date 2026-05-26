/*
 * Copyright 2012-2015 Sergey Ignatov
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

package org.intellij.erlang.resolve;


import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import org.intellij.erlang.psi.ErlangFile;
import org.intellij.erlang.psi.impl.ErlangPsiImplUtil;
import org.intellij.erlang.sdk.ErlangSdkRelease;
import org.intellij.erlang.sdk.ErlangSdkType;
import org.intellij.erlang.utils.ErlangLightPlatformCodeInsightFixtureTestCase;

import java.util.List;

public class ErlangIncludeLibResolveTest extends ErlangLightPlatformCodeInsightFixtureTestCase {
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return new DefaultLightProjectDescriptor() {
      @Override
      public Sdk getSdk() {
        return ErlangSdkType.createMockSdk("testData/mockSdk-R15B02/", ErlangSdkRelease.V_R15B02);
      }
    };
  }

  @Override
  protected String getTestDataPath() {
    return "testData/resolve/includeLib/";
  }

  public void testLatestVersionIsUsed() {
    myFixture.configureByFiles("testappuser.erl",
      "testapp-1.0/ebin/testapp.app", "testapp-1.0/include/testapp.hrl",
      "testapp-2.0/ebin/testapp.app", "testapp-2.0/include/testapp.hrl");

    doTestAppResolveTest("testapp-2.0");
  }

  public void testNoVersionIsUsed() {
    myFixture.configureByFiles("testappuser.erl",
      "testapp-1.0/ebin/testapp.app", "testapp-1.0/include/testapp.hrl",
      "testapp-2.0/ebin/testapp.app", "testapp-2.0/include/testapp.hrl",
      "testapp/ebin/testapp.app", "testapp/include/testapp.hrl");

    doTestAppResolveTest("testapp");
  }

  public void test375() {
    myFixture.configureByFiles("testappuser.erl",
      "testapp-1.0/ebin/testapp.app", "testapp-1.0/include/testapp.hrl",
      "patches/testapp/testapp.app", "patches/include/testapp.hrl");
    doTestAppResolveTest("testapp-1.0");
  }

  public void testResolveByPathWhenApplicationIndexMisses() {
    myFixture.configureByFiles("testappuser.erl", "linked/testapp/include/testapp.hrl");

    doTestAppResolveTest("testapp");
  }

  public void testResolveFromDepsWhenApplicationIndexMisses() {
    myFixture.configureByFiles("testappuser.erl", "deps/testapp/include/testapp.hrl");

    doTestAppResolveTest("testapp");
  }

  public void testResolveFromBuildLibWhenApplicationIndexMisses() {
    myFixture.configureByFiles("testappuser.erl", "_build/emqx-enterprise/lib/testapp/include/testapp.hrl");

    doTestAppResolveTest("testapp");
  }

  public void testLatestVersionByPathWhenApplicationIndexMisses() {
    myFixture.configureByFiles("testappuser.erl",
      "linked/testapp-1.0/include/testapp.hrl",
      "linked/testapp-2.0/include/testapp.hrl");

    doTestAppResolveTest("testapp-2.0");
  }

  public void testResolveOtpIncludeLibFromSdk() {
    setUpProjectSdk();
    myFixture.configureByText("otp_include_user.erl",
      "-module(otp_include_user).\n" +
        "-include_lib(\"eunit/include/eunit.hrl\").\n" +
        "-include_lib(\"common_test/include/ct.hrl\").\n");

    PsiFile otpIncludeUserErl = myFixture.getFile();
    assertTrue(otpIncludeUserErl instanceof ErlangFile);
    List<ErlangFile> directlyIncludedFiles = ErlangPsiImplUtil.getDirectlyIncludedFiles((ErlangFile) otpIncludeUserErl);
    assertEquals(2, directlyIncludedFiles.size());
    assertEquals("eunit.hrl", directlyIncludedFiles.get(0).getName());
    assertEquals("ct.hrl", directlyIncludedFiles.get(1).getName());
  }

  private void doTestAppResolveTest(String expectedAppDirName) {
    PsiFile testappuserErl = myFixture.getFile();
    assertTrue(testappuserErl instanceof ErlangFile);
    List<ErlangFile> directlyIncludedFiles = ErlangPsiImplUtil.getDirectlyIncludedFiles((ErlangFile) testappuserErl);
    assertEquals(1, directlyIncludedFiles.size());
    PsiDirectory ebinDirectory = directlyIncludedFiles.getFirst().getParent();
    assertNotNull(ebinDirectory);
    PsiDirectory appDir = ebinDirectory.getParent();
    assertNotNull(appDir);
    assertEquals(expectedAppDirName, appDir.getName());
  }
}
