// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.CompilationHelper;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider.TargetLicense;
import com.google.devtools.build.lib.analysis.MiddlemanProvider;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TemplateVariableInfo;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Variables;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Variables.Builder;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool;
import com.google.devtools.build.lib.rules.cpp.FdoSupport.FdoException;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Implementation for the cc_toolchain rule.
 */
public class CcToolchain implements RuleConfiguredTargetFactory {

  /** Default attribute name where rules store the reference to cc_toolchain */
  public static final String CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME = ":cc_toolchain";

  /** Default attribute name for the c++ toolchain type */
  public static final String CC_TOOLCHAIN_TYPE_ATTRIBUTE_NAME = "$cc_toolchain_type";

  /**
   * This file (found under the sysroot) may be unconditionally included in every C/C++ compilation.
   */
  private static final PathFragment BUILTIN_INCLUDE_FILE_SUFFIX =
      PathFragment.create("include/stdc-predef.h");

  /*
   * Returns the profile name with the same file name as fdoProfile and an
   * extension that matches {@link FileType}.
   */
  private static String getLLVMProfileFileName(Path fdoProfile, FileType type) {
    if (type.matches(fdoProfile)) {
      return fdoProfile.getBaseName();
    } else {
      return FileSystemUtils.removeExtension(fdoProfile.getBaseName())
          + type.getExtensions().get(0);
    }
  }

  private static final String SYSROOT_START = "%sysroot%/";
  private static final String WORKSPACE_START = "%workspace%/";
  private static final String CROSSTOOL_START = "%crosstool_top%/";
  private static final String PACKAGE_START = "%package(";
  private static final String PACKAGE_END = ")%";

  /**
   * Resolve the given include directory.
   *
   * <p>If it starts with %sysroot%/, that part is replaced with the actual sysroot.
   *
   * <p>If it starts with %workspace%/, that part is replaced with the empty string (essentially
   * making it relative to the build directory).
   *
   * <p>If it starts with %crosstool_top%/ or is any relative path, it is interpreted relative to
   * the crosstool top. The use of assumed-crosstool-relative specifications is considered
   * deprecated, and all such uses should eventually be replaced by "%crosstool_top%/".
   *
   * <p>If it is of the form %package(@repository//my/package)%/folder, then it is interpreted as
   * the named folder in the appropriate package. All of the normal package syntax is supported. The
   * /folder part is optional.
   *
   * <p>It is illegal if it starts with a % and does not match any of the above forms to avoid
   * accidentally silently ignoring misspelled prefixes.
   *
   * <p>If it is absolute, it remains unchanged.
   */
  static PathFragment resolveIncludeDir(
      String s, PathFragment sysroot, PathFragment crosstoolTopPathFragment)
      throws InvalidConfigurationException {
    PathFragment pathPrefix;
    String pathString;
    int packageEndIndex = s.indexOf(PACKAGE_END);
    if (packageEndIndex != -1 && s.startsWith(PACKAGE_START)) {
      String packageString = s.substring(PACKAGE_START.length(), packageEndIndex);
      try {
        pathPrefix = PackageIdentifier.parse(packageString).getSourceRoot();
      } catch (LabelSyntaxException e) {
        throw new InvalidConfigurationException("The package '" + packageString + "' is not valid");
      }
      int pathStartIndex = packageEndIndex + PACKAGE_END.length();
      if (pathStartIndex + 1 < s.length()) {
        if (s.charAt(pathStartIndex) != '/') {
          throw new InvalidConfigurationException(
              "The path in the package for '" + s + "' is not valid");
        }
        pathString = s.substring(pathStartIndex + 1, s.length());
      } else {
        pathString = "";
      }
    } else if (s.startsWith(SYSROOT_START)) {
      if (sysroot == null) {
        throw new InvalidConfigurationException(
            "A %sysroot% prefix is only allowed if the " + "default_sysroot option is set");
      }
      pathPrefix = sysroot;
      pathString = s.substring(SYSROOT_START.length(), s.length());
    } else if (s.startsWith(WORKSPACE_START)) {
      pathPrefix = PathFragment.EMPTY_FRAGMENT;
      pathString = s.substring(WORKSPACE_START.length(), s.length());
    } else {
      pathPrefix = crosstoolTopPathFragment;
      if (s.startsWith(CROSSTOOL_START)) {
        pathString = s.substring(CROSSTOOL_START.length(), s.length());
      } else if (s.startsWith("%")) {
        throw new InvalidConfigurationException(
            "The include path '" + s + "' has an " + "unrecognized %prefix%");
      } else {
        pathString = s;
      }
    }

    PathFragment path = PathFragment.create(pathString);
    if (!path.isNormalized()) {
      throw new InvalidConfigurationException("The include path '" + s + "' is not normalized.");
    }
    return pathPrefix.getRelative(path);
  }

  /*
   * This function checks the format of the input profile data and converts it to
   * the indexed format (.profdata) if necessary.
   */
  private Artifact convertLLVMRawProfileToIndexed(
      Path fdoProfile,
      CppToolchainInfo toolchainInfo,
      RuleContext ruleContext)
      throws InterruptedException {

    Artifact profileArtifact =
        ruleContext.getUniqueDirectoryArtifact(
            "fdo",
            getLLVMProfileFileName(fdoProfile, CppFileTypes.LLVM_PROFILE),
            ruleContext.getBinOrGenfilesDirectory());

    // If the profile file is already in the desired format, symlink to it and return.
    if (CppFileTypes.LLVM_PROFILE.matches(fdoProfile)) {
      ruleContext.registerAction(
          new SymlinkAction(
              ruleContext.getActionOwner(),
              PathFragment.create(fdoProfile.getPathString()),
              profileArtifact,
              "Symlinking LLVM Profile " + fdoProfile.getPathString()));
      return profileArtifact;
    }

    Artifact rawProfileArtifact;

    if (fdoProfile.getBaseName().endsWith(".zip")) {
      // Get the zipper binary for unzipping the profile.
      Artifact zipperBinaryArtifact = ruleContext.getPrerequisiteArtifact(":zipper", Mode.HOST);
      if (zipperBinaryArtifact == null) {
        ruleContext.ruleError("Cannot find zipper binary to unzip the profile");
        return null;
      }

      // TODO(zhayu): find a way to avoid hard-coding cpu architecture here (b/65582760)
      String rawProfileFileName = "fdocontrolz_profile.profraw";
      String cpu = toolchainInfo.getTargetCpu();
      if (!"k8".equals(cpu)) {
        rawProfileFileName = "fdocontrolz_profile-" + cpu + ".profraw";
      }
      rawProfileArtifact =
          ruleContext.getUniqueDirectoryArtifact(
              "fdo", rawProfileFileName, ruleContext.getBinOrGenfilesDirectory());

      // Symlink to the zipped profile file to extract the contents.
      Artifact zipProfileArtifact =
          ruleContext.getUniqueDirectoryArtifact(
              "fdo", fdoProfile.getBaseName(), ruleContext.getBinOrGenfilesDirectory());
      ruleContext.registerAction(
          new SymlinkAction(
              ruleContext.getActionOwner(),
              PathFragment.create(fdoProfile.getPathString()),
              zipProfileArtifact,
              "Symlinking LLVM ZIP Profile " + fdoProfile.getPathString()));

      // Unzip the profile.
      ruleContext.registerAction(
          new SpawnAction.Builder()
              .addInput(zipProfileArtifact)
              .addInput(zipperBinaryArtifact)
              .addOutput(rawProfileArtifact)
              .useDefaultShellEnvironment()
              .setExecutable(zipperBinaryArtifact)
              .setProgressMessage(
                  "LLVMUnzipProfileAction: Generating %s", rawProfileArtifact.prettyPrint())
              .setMnemonic("LLVMUnzipProfileAction")
              .addCommandLine(
                  CustomCommandLine.builder()
                      .addExecPath("xf", zipProfileArtifact)
                      .add(
                          "-d",
                          rawProfileArtifact.getExecPath().getParentDirectory().getSafePathString())
                      .build())
              .build(ruleContext));
    } else {
      rawProfileArtifact =
          ruleContext.getUniqueDirectoryArtifact(
              "fdo",
              getLLVMProfileFileName(fdoProfile, CppFileTypes.LLVM_PROFILE_RAW),
              ruleContext.getBinOrGenfilesDirectory());
      ruleContext.registerAction(
          new SymlinkAction(
              ruleContext.getActionOwner(),
              PathFragment.create(fdoProfile.getPathString()),
              rawProfileArtifact,
              "Symlinking LLVM Raw Profile " + fdoProfile.getPathString()));
    }

    if (toolchainInfo.getToolPathFragment(Tool.LLVM_PROFDATA) == null) {
      ruleContext.ruleError(
          "llvm-profdata not available with this crosstool, needed for profile conversion");
      return null;
    }

    // Convert LLVM raw profile to indexed format.
    ruleContext.registerAction(
        new SpawnAction.Builder()
            .addInput(rawProfileArtifact)
            .addTransitiveInputs(getFiles(ruleContext, "all_files"))
            .addOutput(profileArtifact)
            .useDefaultShellEnvironment()
            .setExecutable(toolchainInfo.getToolPathFragment(Tool.LLVM_PROFDATA))
            .setProgressMessage("LLVMProfDataAction: Generating %s", profileArtifact.prettyPrint())
            .setMnemonic("LLVMProfDataAction")
            .addCommandLine(
                CustomCommandLine.builder()
                    .add("merge")
                    .add("-o")
                    .addExecPath(profileArtifact)
                    .addExecPath(rawProfileArtifact)
                    .build())
            .build(ruleContext));

    return profileArtifact;
  }

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws RuleErrorException, InterruptedException {
    TransitiveInfoCollection lipoContextCollector =
        ruleContext.getPrerequisite(":lipo_context_collector", Mode.DONT_CHECK);
    if (lipoContextCollector != null
        && lipoContextCollector.getProvider(LipoContextProvider.class) == null) {
      ruleContext.ruleError("--lipo_context must point to a cc_binary or a cc_test rule");
      return null;
    }

    CppConfiguration cppConfiguration =
        Preconditions.checkNotNull(ruleContext.getFragment(CppConfiguration.class));
    PlatformConfiguration platformConfig =
        Preconditions.checkNotNull(ruleContext.getFragment(PlatformConfiguration.class));

    CToolchain toolchain = null;
    if (platformConfig
        .getEnabledToolchainTypes()
        .contains(CppHelper.getToolchainTypeFromRuleClass(ruleContext))) {
      toolchain = getToolchainFromAttributes(ruleContext, cppConfiguration);
    }

    CppToolchainInfo toolchainInfo = null;
    if (toolchain != null) {
      try {
        toolchainInfo =
            new CppToolchainInfo(
                toolchain,
                cppConfiguration.getCrosstoolTopPathFragment(),
                cppConfiguration.getCcToolchainRuleLabel());
      } catch (InvalidConfigurationException e) {
        ruleContext.throwWithRuleError(e.getMessage());
      }
    } else {
      toolchainInfo = cppConfiguration.getCppToolchainInfo();
    }

    Path fdoZip = ruleContext.getConfiguration().getCompilationMode() == CompilationMode.OPT
        ? cppConfiguration.getFdoZip()
        : null;
    SkyKey fdoKey =
        FdoSupportValue.key(
            cppConfiguration.getLipoMode(),
            fdoZip,
            cppConfiguration.getFdoInstrument(),
            cppConfiguration.isLLVMOptimizedFdo(toolchainInfo.isLLVMCompiler()));

    SkyFunction.Environment skyframeEnv = ruleContext.getAnalysisEnvironment().getSkyframeEnv();
    FdoSupportValue fdoSupport;
    try {
      fdoSupport = (FdoSupportValue) skyframeEnv.getValueOrThrow(
          fdoKey, FdoException.class, IOException.class);
    } catch (FdoException | IOException e) {
      ruleContext.ruleError("cannot initialize FDO: " + e.getMessage());
      return null;
    }

    if (skyframeEnv.valuesMissing()) {
      return null;
    }

    final Label label = ruleContext.getLabel();
    final NestedSet<Artifact> crosstool = ruleContext.getPrerequisite("all_files", Mode.HOST)
        .getProvider(FileProvider.class).getFilesToBuild();
    final NestedSet<Artifact> crosstoolMiddleman = getFiles(ruleContext, "all_files");
    final NestedSet<Artifact> compile = getFiles(ruleContext, "compiler_files");
    final NestedSet<Artifact> strip = getFiles(ruleContext, "strip_files");
    final NestedSet<Artifact> objcopy = getFiles(ruleContext, "objcopy_files");
    final NestedSet<Artifact> link = getFiles(ruleContext, "linker_files");
    final NestedSet<Artifact> dwp = getFiles(ruleContext, "dwp_files");
    final NestedSet<Artifact> libcLink = inputsForLibc(ruleContext);
    String purposePrefix = Actions.escapeLabel(label) + "_";
    String runtimeSolibDirBase = "_solib_" + "_" + Actions.escapeLabel(label);
    final PathFragment runtimeSolibDir = ruleContext.getConfiguration()
        .getBinFragment().getRelative(runtimeSolibDirBase);

    // Static runtime inputs.
    TransitiveInfoCollection staticRuntimeLibDep = selectDep(ruleContext, "static_runtime_libs",
        toolchainInfo.getStaticRuntimeLibsLabel());
    final NestedSet<Artifact> staticRuntimeLinkInputs;
    final Artifact staticRuntimeLinkMiddleman;
    if (toolchainInfo.supportsEmbeddedRuntimes()) {
      staticRuntimeLinkInputs = staticRuntimeLibDep
          .getProvider(FileProvider.class)
          .getFilesToBuild();
    } else {
      staticRuntimeLinkInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    if (!staticRuntimeLinkInputs.isEmpty()) {
      NestedSet<Artifact> staticRuntimeLinkMiddlemanSet = CompilationHelper.getAggregatingMiddleman(
          ruleContext,
          purposePrefix + "static_runtime_link",
          staticRuntimeLibDep);
      staticRuntimeLinkMiddleman = staticRuntimeLinkMiddlemanSet.isEmpty()
          ? null : Iterables.getOnlyElement(staticRuntimeLinkMiddlemanSet);
    } else {
      staticRuntimeLinkMiddleman = null;
    }

    Preconditions.checkState(
        (staticRuntimeLinkMiddleman == null) == staticRuntimeLinkInputs.isEmpty());

    // Dynamic runtime inputs.
    TransitiveInfoCollection dynamicRuntimeLibDep = selectDep(ruleContext, "dynamic_runtime_libs",
        toolchainInfo.getDynamicRuntimeLibsLabel());
    NestedSet<Artifact> dynamicRuntimeLinkSymlinks;
    List<Artifact> dynamicRuntimeLinkInputs = new ArrayList<>();
    Artifact dynamicRuntimeLinkMiddleman;
    if (toolchainInfo.supportsEmbeddedRuntimes()) {
      NestedSetBuilder<Artifact> dynamicRuntimeLinkSymlinksBuilder = NestedSetBuilder.stableOrder();
      for (Artifact artifact : dynamicRuntimeLibDep
          .getProvider(FileProvider.class).getFilesToBuild()) {
        if (CppHelper.SHARED_LIBRARY_FILETYPES.matches(artifact.getFilename())) {
          dynamicRuntimeLinkInputs.add(artifact);
          dynamicRuntimeLinkSymlinksBuilder.add(
              SolibSymlinkAction.getCppRuntimeSymlink(
                  ruleContext,
                  artifact,
                  toolchainInfo.getSolibDirectory(),
                  runtimeSolibDirBase,
                  ruleContext.getConfiguration()));
        }
      }
      dynamicRuntimeLinkSymlinks = dynamicRuntimeLinkSymlinksBuilder.build();
    } else {
      dynamicRuntimeLinkSymlinks = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    if (!dynamicRuntimeLinkInputs.isEmpty()) {
      List<Artifact> dynamicRuntimeLinkMiddlemanSet =
          CppHelper.getAggregatingMiddlemanForCppRuntimes(
              ruleContext,
              purposePrefix + "dynamic_runtime_link",
              dynamicRuntimeLinkInputs,
              toolchainInfo.getSolibDirectory(),
              runtimeSolibDirBase,
              ruleContext.getConfiguration());
      dynamicRuntimeLinkMiddleman = dynamicRuntimeLinkMiddlemanSet.isEmpty()
          ? null : Iterables.getOnlyElement(dynamicRuntimeLinkMiddlemanSet);
    } else {
      dynamicRuntimeLinkMiddleman = null;
    }

    Preconditions.checkState(
        (dynamicRuntimeLinkMiddleman == null) == dynamicRuntimeLinkSymlinks.isEmpty());

    CppCompilationContext.Builder contextBuilder =
        new CppCompilationContext.Builder(ruleContext);
    CppModuleMap moduleMap = createCrosstoolModuleMap(ruleContext);
    if (moduleMap != null) {
      contextBuilder.setCppModuleMap(moduleMap);
    }
    final CppCompilationContext context = contextBuilder.build();
    boolean supportsParamFiles = ruleContext.attributes().get("supports_param_files", BOOLEAN);
    boolean supportsHeaderParsing =
        ruleContext.attributes().get("supports_header_parsing", BOOLEAN);

    NestedSetBuilder<Pair<String, String>> coverageEnvironment = NestedSetBuilder.compileOrder();

    NestedSet<Artifact> coverage = getOptionalFiles(ruleContext, "coverage_files");
    if (coverage.isEmpty()) {
      coverage = crosstool;
    }

    PathFragment sysroot = calculateSysroot(ruleContext, toolchainInfo.getDefaultSysroot());

    ImmutableList.Builder<PathFragment> builtInIncludeDirectoriesBuilder = ImmutableList.builder();
    for (String s : toolchainInfo.getRawBuiltInIncludeDirectories()) {
      try {
        builtInIncludeDirectoriesBuilder.add(
            resolveIncludeDir(s, sysroot, toolchainInfo.getCrosstoolTopPathFragment()));
      } catch (InvalidConfigurationException e) {
        ruleContext.ruleError(e.getMessage());
      }
    }
    ImmutableList<PathFragment> builtInIncludeDirectories =
        builtInIncludeDirectoriesBuilder.build();

    coverageEnvironment.add(
        Pair.of(
            "COVERAGE_GCOV_PATH", toolchainInfo.getToolPathFragment(Tool.GCOV).getPathString()));
    if (cppConfiguration.getFdoInstrument() != null) {
      coverageEnvironment.add(
          Pair.of("FDO_DIR", cppConfiguration.getFdoInstrument().getPathString()));
    }

    // This tries to convert LLVM profiles to the indexed format if necessary.
    Artifact profileArtifact = null;
    if (cppConfiguration.isLLVMOptimizedFdo(toolchainInfo.isLLVMCompiler())) {
      profileArtifact =
          convertLLVMRawProfileToIndexed(fdoZip, toolchainInfo, ruleContext);
      if (ruleContext.hasErrors()) {
        return null;
      }
    }

    reportInvalidOptions(ruleContext, toolchainInfo);

    CcToolchainProvider ccProvider =
        new CcToolchainProvider(
            getToolchainForSkylark(toolchainInfo),
            cppConfiguration,
            toolchainInfo,
            cppConfiguration.getCrosstoolTopPathFragment(),
            crosstool,
            fullInputsForCrosstool(ruleContext, crosstoolMiddleman),
            compile,
            strip,
            objcopy,
            fullInputsForLink(ruleContext, link),
            ruleContext.getPrerequisiteArtifact("$interface_library_builder", Mode.HOST),
            dwp,
            coverage,
            libcLink,
            staticRuntimeLinkInputs,
            staticRuntimeLinkMiddleman,
            dynamicRuntimeLinkSymlinks,
            dynamicRuntimeLinkMiddleman,
            runtimeSolibDir,
            context,
            supportsParamFiles,
            supportsHeaderParsing,
            getBuildVariables(ruleContext, toolchainInfo.getDefaultSysroot()),
            getBuiltinIncludes(ruleContext),
            coverageEnvironment.build(),
            toolchainInfo.supportsInterfaceSharedObjects()
                ? ruleContext.getPrerequisiteArtifact("$link_dynamic_library_tool", Mode.HOST)
                : null,
            getEnvironment(ruleContext),
            builtInIncludeDirectories,
            sysroot);

    TemplateVariableInfo templateVariableInfo =
        createMakeVariableProvider(cppConfiguration, sysroot);

    RuleConfiguredTargetBuilder builder =
        new RuleConfiguredTargetBuilder(ruleContext)
            .addNativeDeclaredProvider(ccProvider)
            .addNativeDeclaredProvider(templateVariableInfo)
            .addProvider(
                fdoSupport.getFdoSupport().createFdoSupportProvider(ruleContext, profileArtifact))
            .setFilesToBuild(crosstool)
            .addProvider(RunfilesProvider.simple(Runfiles.EMPTY));

    // If output_license is specified on the cc_toolchain rule, override the transitive licenses
    // with that one. This is necessary because cc_toolchain is used in the target configuration,
    // but it is sort-of-kind-of a tool, but various parts of it are linked into the output...
    // ...so we trust the judgment of the author of the cc_toolchain rule to figure out what
    // licenses should be propagated to C++ targets.
    // TODO(elenairina): Remove this and use Attribute.Builder.useOutputLicenses() on the
    // :cc_toolchain attribute instead.
    final License outputLicense =
        ruleContext.getRule().getToolOutputLicense(ruleContext.attributes());
    if (outputLicense != null && !outputLicense.equals(License.NO_LICENSE)) {
      final NestedSet<TargetLicense> license = NestedSetBuilder.create(Order.STABLE_ORDER,
          new TargetLicense(ruleContext.getLabel(), outputLicense));
      LicensesProvider licensesProvider = new LicensesProvider() {
        @Override
        public NestedSet<TargetLicense> getTransitiveLicenses() {
          return license;
        }

        @Override
        public TargetLicense getOutputLicenses() {
          return new TargetLicense(label, outputLicense);
        }

        @Override
        public boolean hasOutputLicenses() {
          return true;
        }

      };

      builder.add(LicensesProvider.class, licensesProvider);
    }

    return builder.build();
  }

  private void reportInvalidOptions(RuleContext ruleContext, CppToolchainInfo toolchain) {
    CppOptions options = ruleContext.getConfiguration().getOptions().get(CppOptions.class);
    CppConfiguration config = ruleContext.getFragment(CppConfiguration.class);
    if (options.fissionModes.contains(config.getCompilationMode())
        && !toolchain.supportsFission()) {
      ruleContext.ruleWarning(
          "Fission is not supported by this crosstool.  Please use a "
              + "supporting crosstool to enable fission");
    }
    if (options.buildTestDwp
        && !(toolchain.supportsFission() && config.fissionIsActiveForCurrentCompilationMode())) {
      ruleContext.ruleWarning(
          "Test dwp file requested, but Fission is not enabled.  To generate a "
              + "dwp for the test executable, use '--fission=yes' with a toolchain that supports "
              + "Fission to build statically.");
    }
  }

  private static String getSkylarkValueForTool(Tool tool, CppToolchainInfo cppToolchainInfo) {
    PathFragment toolPath = cppToolchainInfo.getToolPathFragment(tool);
    return toolPath != null ? toolPath.getPathString() : "";
  }

  private static ImmutableMap<String, Object> getToolchainForSkylark(
      CppToolchainInfo cppToolchainInfo) {
    return ImmutableMap.<String, Object>builder()
        .put("objcopy_executable", getSkylarkValueForTool(Tool.OBJCOPY, cppToolchainInfo))
        .put("compiler_executable", getSkylarkValueForTool(Tool.GCC, cppToolchainInfo))
        .put("preprocessor_executable", getSkylarkValueForTool(Tool.CPP, cppToolchainInfo))
        .put("nm_executable", getSkylarkValueForTool(Tool.NM, cppToolchainInfo))
        .put("objdump_executable", getSkylarkValueForTool(Tool.OBJDUMP, cppToolchainInfo))
        .put("ar_executable", getSkylarkValueForTool(Tool.AR, cppToolchainInfo))
        .put("strip_executable", getSkylarkValueForTool(Tool.STRIP, cppToolchainInfo))
        .put("ld_executable", getSkylarkValueForTool(Tool.LD, cppToolchainInfo))
        .build();
  }

  private CToolchain getToolchainFromAttributes(
      RuleContext ruleContext, CppConfiguration cppConfiguration) throws RuleErrorException {
    if (ruleContext.attributes().get("cpu", Type.STRING).isEmpty()) {
      ruleContext.throwWithRuleError("Using cc_toolchain target requires the attribute 'cpu' "
          + "to be present");
    }


    String cpu = ruleContext.attributes().get("cpu", Type.STRING);
    String compiler = ruleContext.attributes().get("compiler", Type.STRING);
    if (compiler.isEmpty()) {
      compiler = null;
    }
    String libc = ruleContext.attributes().get("libc", Type.STRING);
    if (libc.isEmpty()) {
      libc = null;
    }
    CrosstoolConfigurationIdentifier config =
        new CrosstoolConfigurationIdentifier(cpu, compiler, libc);

    try {
      return CrosstoolConfigurationLoader.selectToolchain(
          cppConfiguration.getCrosstoolFile().getProto(),
          config,
          cppConfiguration.getLipoMode(),
          cppConfiguration.shouldConvertLipoToThinLto(),
          cppConfiguration.getCpuTransformer());
    } catch (InvalidConfigurationException e) {
      ruleContext.throwWithRuleError(
          String.format("Error while using cc_toolchain: %s", e.getMessage()));
      return null;
    }
  }

  private ImmutableList<Artifact> getBuiltinIncludes(RuleContext ruleContext) {
    ImmutableList.Builder<Artifact> result = ImmutableList.builder();
    for (Artifact artifact : inputsForLibc(ruleContext)) {
      if (artifact.getExecPath().endsWith(BUILTIN_INCLUDE_FILE_SUFFIX)) {
        result.add(artifact);
      }
    }

    return result.build();
  }

  private NestedSet<Artifact> inputsForLibc(RuleContext ruleContext) {
    TransitiveInfoCollection libc = ruleContext.getPrerequisite(":libc_top", Mode.TARGET);
    return libc != null
        ? libc.getProvider(FileProvider.class).getFilesToBuild()
        : NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER);
  }

  private NestedSet<Artifact> fullInputsForCrosstool(RuleContext ruleContext,
      NestedSet<Artifact> crosstoolMiddleman) {
    return NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(crosstoolMiddleman)
        .addTransitive(AnalysisUtils.getMiddlemanFor(ruleContext, ":libc_top", Mode.TARGET))
        .build();
  }

  /**
   * Returns the crosstool-derived link action inputs for a given rule. Adds the given set of
   * artifacts as extra inputs.
   */
  protected NestedSet<Artifact> fullInputsForLink(
      RuleContext ruleContext, NestedSet<Artifact> link) {
    return NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(link)
        .addTransitive(AnalysisUtils.getMiddlemanFor(ruleContext, ":libc_top", Mode.TARGET))
        .add(ruleContext.getPrerequisiteArtifact("$interface_library_builder", Mode.HOST))
        .add(ruleContext.getPrerequisiteArtifact("$link_dynamic_library_tool", Mode.HOST))
        .build();
  }

  private CppModuleMap createCrosstoolModuleMap(RuleContext ruleContext) {
    if (ruleContext.getPrerequisite("module_map", Mode.HOST) == null) {
      return null;
    }
    Artifact moduleMapArtifact = ruleContext.getPrerequisiteArtifact("module_map", Mode.HOST);
    if (moduleMapArtifact == null) {
      return null;
    }
    return new CppModuleMap(moduleMapArtifact, "crosstool");
  }

  private TransitiveInfoCollection selectDep(
      RuleContext ruleContext, String attribute, Label label) {
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites(attribute, Mode.TARGET)) {
      if (dep.getLabel().equals(label)) {
        return dep;
      }
    }

    return ruleContext.getPrerequisites(attribute, Mode.TARGET).get(0);
  }

  private NestedSet<Artifact> getFiles(RuleContext context, String attribute) {
    TransitiveInfoCollection dep = context.getPrerequisite(attribute, Mode.HOST);
    MiddlemanProvider middlemanProvider = dep.getProvider(MiddlemanProvider.class);
    // We use the middleman if we can (if the dep is a filegroup), otherwise, just the regular
    // filesToBuild (e.g. if it is a simple input file)
    return middlemanProvider != null
        ? middlemanProvider.getMiddlemanArtifact()
        : dep.getProvider(FileProvider.class).getFilesToBuild();
  }

  private NestedSet<Artifact> getOptionalFiles(RuleContext context, String attribute) {
    TransitiveInfoCollection dep = context.getPrerequisite(attribute, Mode.HOST);
    return dep != null
        ? getFiles(context, attribute)
        : NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  private TemplateVariableInfo createMakeVariableProvider(
      CppConfiguration cppConfiguration, PathFragment sysroot) {

    HashMap<String, String> makeVariables =
        new HashMap<>(cppConfiguration.getAdditionalMakeVariables());

    // Overwrite the CC_FLAGS variable to include sysroot, if it's available.
    if (sysroot != null) {
      String sysrootFlag = "--sysroot=" + sysroot;
      String ccFlags = makeVariables.get(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME);
      ccFlags = ccFlags.isEmpty() ? sysrootFlag : ccFlags + " " + sysrootFlag;
      makeVariables.put(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME, ccFlags);
    }
    return new TemplateVariableInfo(ImmutableMap.copyOf(makeVariables));
  }

  /**
   * Returns {@link Variables} instance with build variables that only depend on the toolchain.
   *
   * @param ruleContext the rule context
   * @param defaultSysroot the default sysroot
   * @throws RuleErrorException if there are configuration errors making it impossible to resolve
   *     certain build variables of this toolchain
   */
  private final Variables getBuildVariables(RuleContext ruleContext, PathFragment defaultSysroot)
      throws RuleErrorException {
    Variables.Builder variables = new Variables.Builder();

    PathFragment sysroot = calculateSysroot(ruleContext, defaultSysroot);
    if (sysroot != null) {
      variables.addStringVariable(CppModel.SYSROOT_VARIABLE_NAME, sysroot.getPathString());
    }

    addBuildVariables(ruleContext, variables);

    return variables.build();
  }

  /**
   * Add local build variables from subclasses into {@link Variables} returned from {@link
   * #getBuildVariables(RuleContext, PathFragment)}.
   *
   * <p>This method is meant to be overridden by subclasses of CcToolchain.
   */
  protected void addBuildVariables(RuleContext ruleContext, Builder variables)
      throws RuleErrorException {
    // To be overridden in subclasses.
  }

  /**
   * Returns a map of environment variables to be added to the compile actions created for this
   * toolchain. Ideally, this will get replaced by features, which also allow setting env variables.
   *
   * @param ruleContext the rule context
   */
  protected ImmutableMap<String, String> getEnvironment(RuleContext ruleContext) {
    return ImmutableMap.<String, String>of();
  }

  private PathFragment calculateSysroot(RuleContext ruleContext, PathFragment defaultSysroot) {

    TransitiveInfoCollection sysrootTarget = ruleContext.getPrerequisite(":libc_top", Mode.TARGET);
    if (sysrootTarget == null) {
      return defaultSysroot;
    }

    return sysrootTarget.getLabel().getPackageFragment();
  }
}
