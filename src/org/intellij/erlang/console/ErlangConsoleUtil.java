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

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import org.intellij.erlang.jps.model.JpsErlangSdkType;
import org.intellij.erlang.sdk.ErlangSdkType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ErlangConsoleUtil {
  public static final String EUNIT_FAILURE_PATH = "\\[\\{file,\"" + FileReferenceFilter.PATH_MACROS + "\"\\},\\{line," + FileReferenceFilter.LINE_MACROS + "\\}\\]";
  public static final String EUNIT_ERROR_PATH = FileReferenceFilter.PATH_MACROS + ", line " + FileReferenceFilter.LINE_MACROS;
  public static final String COMPILATION_ERROR_PATH = FileReferenceFilter.PATH_MACROS + ":" + FileReferenceFilter.LINE_MACROS;
  private static final Pattern APP_DEPENDENCY_SECTIONS_PATTERN =
    Pattern.compile("\\{\\s*(applications|included_applications|optional_applications)\\s*,\\s*\\[(.*?)]\\s*}", Pattern.DOTALL);
  private static final Pattern ERLANG_ATOM_PATTERN =
    Pattern.compile("'((?:\\\\.|[^'])*)'|([a-z][a-zA-Z0-9_@]*)");

  private ErlangConsoleUtil() {
  }

  public static void attachFilters(@NotNull Project project, @NotNull ConsoleView consoleView) {
    consoleView.addMessageFilter(new FileReferenceFilter(project, COMPILATION_ERROR_PATH));
    consoleView.addMessageFilter(new FileReferenceFilter(project, EUNIT_ERROR_PATH));
    consoleView.addMessageFilter(new FileReferenceFilter(project, EUNIT_FAILURE_PATH));
  }

  @NotNull
  public static List<String> getCodePath(@NotNull Module module, boolean forTests) {
    return getCodePath(module.getProject(), module, forTests);
  }

  @NotNull
  public static List<String> getCodePath(@NotNull Project project, @Nullable Module module, boolean useTestOutputPath) {
    final Set<Module> codePathModules = new HashSet<>();
    if (module != null) {
      ModuleRootManager moduleRootMgr = ModuleRootManager.getInstance(module);
      moduleRootMgr.orderEntries().recursively().forEachModule(dependencyModule -> {
        codePathModules.add(dependencyModule);
        return true;
      });
    }
    else {
      codePathModules.addAll(Arrays.asList(ModuleManager.getInstance(project).getModules()));
    }

    List<String> codePath = new ArrayList<>(codePathModules.size() * 2);
    Set<String> addedCodePaths = new HashSet<>();
    for (Module codePathModule : codePathModules) {
      ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(codePathModule);
      CompilerModuleExtension compilerModuleExt =
        moduleRootManager.getModuleExtension(CompilerModuleExtension.class);
      VirtualFile buildOutput = useTestOutputPath && codePathModule == module ?
        getCompilerOutputPathForTests(compilerModuleExt) : 
        compilerModuleExt.getCompilerOutputPath();
      addCodePath(codePath, addedCodePaths, buildOutput);
      for (VirtualFile contentRoot : ModuleRootManager.getInstance(codePathModule).getContentRoots()) {
        addCodePath(codePath, addedCodePaths, contentRoot);
        addCodePath(codePath, addedCodePaths, contentRoot.findChild("ebin"));
        if (useTestOutputPath) {
          addCodePath(codePath, addedCodePaths, contentRoot.findChild("test"));
        }
        addRebarBuildCodePaths(codePath, addedCodePaths, project, contentRoot, useTestOutputPath);
      }
    }

    return codePath;
  }

  private static void addCodePath(@NotNull List<String> codePath,
                                  @NotNull Set<String> addedCodePaths,
                                  @Nullable VirtualFile file) {
    if (file == null || !file.isDirectory()) return;

    String path = getProcessPath(file);
    if (addedCodePaths.add(path)) {
      codePath.add("-pa");
      codePath.add(path);
    }
  }

  private static void addRebarBuildCodePaths(@NotNull List<String> codePath,
                                             @NotNull Set<String> addedCodePaths,
                                             @NotNull Project project,
                                             @NotNull VirtualFile contentRoot,
                                             boolean includeTests) {
    String appName = contentRoot.getName();
    for (VirtualFile root : getCandidateBuildRoots(project, contentRoot)) {
      VirtualFile buildRoot = root.findChild("_build");
      if (buildRoot == null || !buildRoot.isDirectory()) continue;

      for (VirtualFile profileRoot : buildRoot.getChildren()) {
        VirtualFile libRoot = profileRoot.findChild("lib");
        if (libRoot == null || !libRoot.isDirectory()) continue;

        Map<String, VirtualFile> appBuildRoots = getAppBuildRoots(libRoot);
        Set<String> runtimeApps = getRuntimeApplicationClosure(appName, appBuildRoots);
        if (runtimeApps.isEmpty()) {
          addAllRebarBuildCodePaths(codePath, addedCodePaths, appName, libRoot, includeTests);
          continue;
        }

        for (String runtimeApp : runtimeApps) {
          VirtualFile appBuildRoot = appBuildRoots.get(runtimeApp);
          if (appBuildRoot == null) continue;
          addCodePath(codePath, addedCodePaths, appBuildRoot.findChild("ebin"));
          if (includeTests && appName.equals(runtimeApp)) {
            addCodePath(codePath, addedCodePaths, appBuildRoot.findChild("test"));
          }
        }
      }
    }
  }

  @NotNull
  private static Map<String, VirtualFile> getAppBuildRoots(@NotNull VirtualFile libRoot) {
    Map<String, VirtualFile> appBuildRoots = new LinkedHashMap<>();
    for (VirtualFile appBuildRoot : libRoot.getChildren()) {
      if (appBuildRoot.isDirectory()) {
        appBuildRoots.put(appBuildRoot.getName(), appBuildRoot);
      }
    }
    return appBuildRoots;
  }

  private static void addAllRebarBuildCodePaths(@NotNull List<String> codePath,
                                                @NotNull Set<String> addedCodePaths,
                                                @NotNull String appName,
                                                @NotNull VirtualFile libRoot,
                                                boolean includeTests) {
    for (VirtualFile appBuildRoot : libRoot.getChildren()) {
      addCodePath(codePath, addedCodePaths, appBuildRoot.findChild("ebin"));
      if (includeTests && appName.equals(appBuildRoot.getName())) {
        addCodePath(codePath, addedCodePaths, appBuildRoot.findChild("test"));
      }
    }
  }

  @NotNull
  private static Set<String> getRuntimeApplicationClosure(@NotNull String appName,
                                                         @NotNull Map<String, VirtualFile> appBuildRoots) {
    if (!appBuildRoots.containsKey(appName)) return Collections.emptySet();

    Set<String> runtimeApps = new LinkedHashSet<>();
    Queue<String> appQueue = new ArrayDeque<>();
    runtimeApps.add(appName);
    appQueue.add(appName);
    while (!appQueue.isEmpty()) {
      String currentApp = appQueue.remove();
      VirtualFile appBuildRoot = appBuildRoots.get(currentApp);
      if (appBuildRoot == null) continue;

      Set<String> dependencies = getApplicationDependencies(appBuildRoot, currentApp);
      if (dependencies == null) return Collections.emptySet();

      for (String dependency : dependencies) {
        if (appBuildRoots.containsKey(dependency) && runtimeApps.add(dependency)) {
          appQueue.add(dependency);
        }
      }
    }

    return runtimeApps;
  }

  @Nullable
  private static Set<String> getApplicationDependencies(@NotNull VirtualFile appBuildRoot, @NotNull String appName) {
    VirtualFile applicationFile = findApplicationFile(appBuildRoot, appName);
    if (applicationFile == null) return null;

    String text;
    try {
      text = VfsUtilCore.loadText(applicationFile);
    }
    catch (IOException e) {
      return null;
    }

    Set<String> dependencies = new LinkedHashSet<>();
    Matcher sectionsMatcher = APP_DEPENDENCY_SECTIONS_PATTERN.matcher(text);
    while (sectionsMatcher.find()) {
      Matcher atomMatcher = ERLANG_ATOM_PATTERN.matcher(sectionsMatcher.group(2));
      while (atomMatcher.find()) {
        String quotedAtom = atomMatcher.group(1);
        String atom = quotedAtom != null ? quotedAtom : atomMatcher.group(2);
        if (atom != null) {
          dependencies.add(StringUtil.unescapeStringCharacters(atom));
        }
      }
    }
    return dependencies;
  }

  @Nullable
  private static VirtualFile findApplicationFile(@NotNull VirtualFile appBuildRoot, @NotNull String appName) {
    VirtualFile ebin = appBuildRoot.findChild("ebin");
    if (ebin != null) {
      VirtualFile applicationFile = ebin.findChild(appName + ".app");
      if (applicationFile != null && !applicationFile.isDirectory()) return applicationFile;
    }

    VirtualFile src = appBuildRoot.findChild("src");
    if (src != null) {
      VirtualFile applicationFile = src.findChild(appName + ".app.src");
      if (applicationFile != null && !applicationFile.isDirectory()) return applicationFile;
    }

    return null;
  }

  @NotNull
  private static Set<VirtualFile> getCandidateBuildRoots(@NotNull Project project, @NotNull VirtualFile contentRoot) {
    LinkedHashSet<VirtualFile> roots = new LinkedHashSet<>();
    ContainerUtil.addIfNotNull(roots, ProjectUtil.guessProjectDir(project));
    VirtualFile current = contentRoot;
    while (current != null) {
      roots.add(current);
      current = current.getParent();
    }
    return roots;
  }

  @NotNull
  public static String getProcessPath(@NotNull VirtualFile file) {
    return getProcessPath(file.getPath());
  }

  @NotNull
  public static String getProcessPath(@NotNull String path) {
    String normalizedPath = PathUtil.toSystemIndependentName(path);
    int remoteRootStart = normalizedPath.indexOf("@/");
    if ((normalizedPath.startsWith("//") || normalizedPath.startsWith("\\\\")) && remoteRootStart >= 0) {
      return normalizedPath.substring(remoteRootStart + 1);
    }
    return normalizedPath;
  }

  @Nullable
  private static VirtualFile getCompilerOutputPathForTests(CompilerModuleExtension module) {
    VirtualFile testPath = module.getCompilerOutputPathForTests();
    return testPath == null || !testPath.exists() ? module.getCompilerOutputPath() : testPath;
  }

  @NotNull
  static String getWorkingDirPath(@NotNull Project project, @NotNull String workingDirPath) {
    if (workingDirPath.isEmpty()) {
      return ObjectUtils.notNull(project.getBasePath(), "");
    }
    return workingDirPath;
  }

  @NotNull
  public static String getErlPath(@NotNull Project project, @Nullable Module module) throws ExecutionException {
    Sdk sdk = getErlangSdk(project, module);
    if (sdk != null) {
      String sdkHome = sdk.getHomePath();
      if (sdkHome == null) {
        throw new ExecutionException("Erlang SDK home path is not configured");
      }
      return getProcessPath(JpsErlangSdkType.getByteCodeInterpreterExecutable(sdkHome).getPath());
    }

    Sdk configuredSdk = getConfiguredSdk(project, module);
    String inferredErlPath = getInferredErlPath(configuredSdk);
    if (inferredErlPath != null) {
      return inferredErlPath;
    }

    throw new ExecutionException("Erlang SDK is not configured");
  }

  @Nullable
  public static Sdk getErlangSdk(@NotNull Project project, @Nullable Module module) {
    Sdk configuredSdk = getConfiguredSdk(project, module);
    if (isErlangSdk(configuredSdk)) {
      return configuredSdk;
    }

    Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
    return isErlangSdk(projectSdk) ? projectSdk : null;
  }

  @Nullable
  private static Sdk getConfiguredSdk(@NotNull Project project, @Nullable Module module) {
    if (module != null) {
      Sdk moduleSdk = ModuleRootManager.getInstance(module).getSdk();
      if (moduleSdk != null) {
        return moduleSdk;
      }
    }

    return ProjectRootManager.getInstance(project).getProjectSdk();
  }

  public static boolean isErlangSdk(@Nullable Sdk sdk) {
    return sdk != null && sdk.getSdkType() == ErlangSdkType.getInstance();
  }

  @Nullable
  static String getInferredErlPath(@Nullable Sdk sdk) {
    if (!isLikelyElixirSdk(sdk)) return null;

    String sdkHome = sdk.getHomePath();
    if (sdkHome == null) return null;

    String erlangSdkHome = inferErlangSdkHomeFromElixirSdkHome(sdkHome);
    if (erlangSdkHome != null) {
      return getProcessPath(JpsErlangSdkType.getByteCodeInterpreterExecutable(erlangSdkHome).getPath());
    }

    return JpsErlangSdkType.getExecutableFileName("erl");
  }

  @Nullable
  static String inferErlangSdkHomeFromElixirSdkHome(@NotNull String elixirSdkHome) {
    String normalizedHome = StringUtil.trimEnd(PathUtil.toSystemIndependentName(elixirSdkHome), "/");
    String lastSegment = PathUtil.getFileName(normalizedHome);
    if (!"elixir".equalsIgnoreCase(lastSegment)) return null;

    int lastSeparatorIndex = normalizedHome.lastIndexOf('/');
    if (lastSeparatorIndex < 0) return null;

    return normalizedHome.substring(0, lastSeparatorIndex) + "/erlang";
  }

  private static boolean isLikelyElixirSdk(@Nullable Sdk sdk) {
    if (sdk == null) return false;

    String sdkTypeName = sdk.getSdkType().getName();
    if (StringUtil.containsIgnoreCase(sdkTypeName, "elixir")) return true;

    String sdkHome = sdk.getHomePath();
    return sdkHome != null && StringUtil.containsIgnoreCase(PathUtil.toSystemIndependentName(sdkHome), "/elixir");
  }
}

