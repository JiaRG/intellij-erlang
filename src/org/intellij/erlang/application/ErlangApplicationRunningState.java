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

package org.intellij.erlang.application;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.PathUtil;
import org.intellij.erlang.console.ErlangConsoleUtil;
import org.intellij.erlang.runconfig.ErlangRunningState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class ErlangApplicationRunningState extends ErlangRunningState {
  private final ErlangApplicationConfiguration myConfiguration;

  public ErlangApplicationRunningState(ExecutionEnvironment env, Module module, ErlangApplicationConfiguration configuration) {
    super(env, module);
    myConfiguration = configuration;
  }

  @Override
  protected boolean useTestCodePath() {
    return myConfiguration.isUseTestCodePath();
  }

  @Override
  protected boolean isNoShellMode() {
    return true;
  }

  @Override
  protected boolean isStopErlang() {
    return myConfiguration.stopErlang();
  }

  @Override
  protected List<String> getErlFlags() {
    return StringUtil.split(myConfiguration.getErlFlags(), " ");
  }

  @Override
  protected void setBeforeEntryPoint(@NotNull GeneralCommandLine commandLine) throws ExecutionException {
    String entryPointFilePath = getEntryPointFilePath();
    if (entryPointFilePath == null) return;

    String sourcePath = ErlangConsoleUtil.getProcessPath(entryPointFilePath);
    String entryPointOutputPath = getEntryPointOutputPath(entryPointFilePath);
    String outputPath = ErlangConsoleUtil.getProcessPath(entryPointOutputPath != null ? entryPointOutputPath : getParentPath(sourcePath));
    String sourceDir = getParentPath(sourcePath);
    String includeDir = getAppRootIncludePath(sourceDir);
    String appRoot = getAppRootPath(sourceDir);

    commandLine.addParameters("-pa", outputPath);
    commandLine.addParameters("-eval", getCompileExpression(sourcePath, outputPath, sourceDir, includeDir, appRoot));
  }

  @Nullable
  private String getEntryPointFilePath() throws ExecutionException {
    String configuredPath = StringUtil.nullize(myConfiguration.getEntryPointFilePath());
    if (configuredPath != null) return configuredPath;

    ErlangEntryPoint entryPoint = getEntryPoint();
    GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(getModule(), myConfiguration.isUseTestCodePath());
    Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(entryPoint.getModuleName() + ".erl", scope);
    if (files.isEmpty()) return null;

    VirtualFile first = null;
    for (VirtualFile file : files) {
      if (first == null) first = file;
      VirtualFile parent = file.getParent();
      if (myConfiguration.isUseTestCodePath() && parent != null && "test".equals(parent.getName())) {
        return file.getPath();
      }
    }
    return first != null ? first.getPath() : null;
  }

  @Nullable
  private String getEntryPointOutputPath(@NotNull String entryPointFilePath) {
    CompilerModuleExtension compilerModuleExtension = CompilerModuleExtension.getInstance(getModule());
    String outputUrl = myConfiguration.isUseTestCodePath() ?
                       compilerModuleExtension.getCompilerOutputUrlForTests() :
                       compilerModuleExtension.getCompilerOutputUrl();
    if (StringUtil.isNotEmpty(outputUrl)) {
      return VfsUtilCore.urlToPath(outputUrl);
    }

    String configuredPath = StringUtil.nullize(myConfiguration.getEntryPointOutputPath());
    if (configuredPath != null) return configuredPath;

    return getParentPath(entryPointFilePath);
  }

  @NotNull
  private static String getCompileExpression(@NotNull String sourcePath,
                                             @NotNull String outputPath,
                                             @NotNull String sourceDir,
                                             @NotNull String includeDir,
                                             @NotNull String appRoot) {
    return "OutDir=\"" + escapeErlangString(outputPath) + "\", " +
      "Source=\"" + escapeErlangString(sourcePath) + "\", " +
      "SourceTestDir=filename:join([\"" + escapeErlangString(appRoot) + "\", \"test\"]), " +
      "TargetTestDir=filename:join([filename:dirname(OutDir), \"test\"]), " +
      "ok=filelib:ensure_dir(filename:join(OutDir, \"dummy.beam\")), " +
      "case filelib:is_dir(SourceTestDir) of " +
      "true -> ok=filelib:ensure_dir(filename:join(TargetTestDir, \"dummy\")), " +
      "{ok,TestEntries}=file:list_dir(SourceTestDir), " +
      "lists:foreach(fun(TestEntry) -> SourceEntry=filename:join(SourceTestDir, TestEntry), " +
      "TargetEntry=filename:join(TargetTestDir, TestEntry), " +
      "case file:read_link_info(TargetEntry) of {error,enoent} -> " +
      "case file:make_symlink(SourceEntry, TargetEntry) of ok->ok; {error,eexist}->ok; _->ok end; _ -> ok end end, TestEntries); " +
      "false -> ok end, " +
      "CompileResult=compile:file(Source, " +
      "[report_errors, report_warnings, export_all, {outdir,OutDir}, " +
      "{i,\"" + escapeErlangString(sourceDir) + "\"}, {i,\"" + escapeErlangString(includeDir) + "\"}]), " +
      "Module=case CompileResult of {ok,M}->M; {ok,M,_}->M; Other->erlang:error({compile_failed,Other}) end, " +
      "code:purge(Module), code:delete(Module), " +
      "case code:load_abs(filename:join(OutDir, atom_to_list(Module))) of " +
      "{module,Module}->ok; {error,Reason}->erlang:error({load_failed,Module,Reason}) end.";
  }

  @NotNull
  private static String getParentPath(@NotNull String path) {
    String normalizedPath = PathUtil.toSystemIndependentName(path);
    int separatorIndex = normalizedPath.lastIndexOf('/');
    return separatorIndex > 0 ? normalizedPath.substring(0, separatorIndex) : normalizedPath;
  }

  @NotNull
  private static String getAppRootIncludePath(@NotNull String sourceDir) {
    String sourceDirName = PathUtil.getFileName(sourceDir);
    if (!"src".equals(sourceDirName) && !"test".equals(sourceDirName)) return sourceDir;

    return getAppRootPath(sourceDir) + "/include";
  }

  @NotNull
  private static String getAppRootPath(@NotNull String sourceDir) {
    String sourceDirName = PathUtil.getFileName(sourceDir);
    if (!"src".equals(sourceDirName) && !"test".equals(sourceDirName)) return sourceDir;
    return getParentPath(sourceDir);
  }

  @NotNull
  private static String escapeErlangString(@NotNull String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  @Nullable
  @Override
  public ErlangEntryPoint getEntryPoint() throws ExecutionException {
    ErlangEntryPoint entryPoint = ErlangEntryPoint.fromModuleAndFunction(myConfiguration.getModuleAndFunction(), myConfiguration.getParams());
    if (entryPoint == null) {
      throw new ExecutionException("Invalid entry point");
    }
    return entryPoint;
  }

  @NotNull
  @Override
  protected String getEntryPointExpression(@NotNull ErlangEntryPoint entryPoint) {
    return "io:format(\"~p~n\", [" + getEntryPointCall(entryPoint) + "]).";
  }

  @Nullable
  @Override
  public String getWorkDirectory() {
    return myConfiguration.getWorkDirectory();
  }

  @NotNull
  @Override
  public ConsoleView createConsoleView(Executor executor) {
    TextConsoleBuilder consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(myConfiguration.getProject());
    return consoleBuilder.getConsole();
  }
}
