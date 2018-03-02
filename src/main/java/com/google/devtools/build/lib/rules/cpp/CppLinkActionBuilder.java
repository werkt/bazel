// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.CollectionUtils;
import com.google.devtools.build.lib.collect.ImmutableIterable;
import com.google.devtools.build.lib.collect.IterablesChain;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.rules.cpp.CcLinkParams.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Variables;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Variables.LibraryToLinkValue;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Variables.SequenceBuilder;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Variables.VariablesExtension;
import com.google.devtools.build.lib.rules.cpp.CppLinkAction.Context;
import com.google.devtools.build.lib.rules.cpp.CppLinkAction.LinkArtifactFactory;
import com.google.devtools.build.lib.rules.cpp.Link.LinkStaticness;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.Link.Staticness;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import javax.annotation.Nullable;

/** Builder class to construct {@link CppLinkAction}s. */
public class CppLinkActionBuilder {

  /** A build variable for entries in the linker runtime search path (usually set by -rpath flag) */
  public static final String RUNTIME_LIBRARY_SEARCH_DIRECTORIES_VARIABLE =
      "runtime_library_search_directories";

  public static final String LIBRARY_SEARCH_DIRECTORIES_VARIABLE = "library_search_directories";

  /** A build variable for flags providing files to link as inputs in the linker invocation */
  public static final String LIBRARIES_TO_LINK_VARIABLE = "libraries_to_link";

  /**
   * A build variable for thinlto param file produced by thinlto-indexing action and consumed by
   * normal linking actions.
   */
  public static final String THINLTO_PARAM_FILE_VARIABLE = "thinlto_param_file";

  public static final String DEF_FILE_PATH_VARIABLE = "def_file_path";

  /**
   * A build variable to let thinlto know where it should write linker flags when indexing.
   */
  public static final String THINLTO_INDEXING_PARAM_FILE_VARIABLE = "thinlto_indexing_param_file";

  public static final String THINLTO_PREFIX_REPLACE_VARIABLE = "thinlto_prefix_replace";

  /**
   * A build variable to let the LTO indexing step know how to map from the minimized bitcode file
   * to the full bitcode file used by the LTO Backends.
   */
  public static final String THINLTO_OBJECT_SUFFIX_REPLACE_VARIABLE =
      "thinlto_object_suffix_replace";

  /**
   * A build variable for linker param file created by Bazel to overcome the command line length
   * limit.
   */
  public static final String LINKER_PARAM_FILE_VARIABLE = "linker_param_file";

  /** A build variable for the execpath of the output of the linker. */
  public static final String OUTPUT_EXECPATH_VARIABLE = "output_execpath";

  /** A build variable setting if interface library should be generated. */
  public static final String GENERATE_INTERFACE_LIBRARY_VARIABLE = "generate_interface_library";

  /** A build variable for the path to the interface library builder tool. */
  public static final String INTERFACE_LIBRARY_BUILDER_VARIABLE = "interface_library_builder_path";

  /** A build variable for the input for the interface library builder tool. */
  public static final String INTERFACE_LIBRARY_INPUT_VARIABLE = "interface_library_input_path";

  /** A build variable for the path where to generate interface library using the builder tool. */
  public static final String INTERFACE_LIBRARY_OUTPUT_VARIABLE = "interface_library_output_path";

  /** A build variable for hard-coded linker flags currently only known by bazel. */
  public static final String LEGACY_LINK_FLAGS_VARIABLE = "legacy_link_flags";

  /** A build variable giving a path to which to write symbol counts. */
  public static final String SYMBOL_COUNTS_OUTPUT_VARIABLE = "symbol_counts_output";

  /** A build variable giving linkstamp paths. */
  public static final String LINKSTAMP_PATHS_VARIABLE = "linkstamp_paths";

  /** A build variable whose presence indicates that PIC code should be generated. */
  public static final String FORCE_PIC_VARIABLE = "force_pic";

  /** A build variable whose presence indicates that the debug symbols should be stripped. */
  public static final String STRIP_DEBUG_SYMBOLS_VARIABLE = "strip_debug_symbols";

  @Deprecated
  public static final String IS_CC_TEST_LINK_ACTION_VARIABLE = "is_cc_test_link_action";

  /** A build variable whose presence indicates that this action is a cc_test linking action. */
  public static final String IS_CC_TEST_VARIABLE = "is_cc_test";

  /**
   * Temporary build variable for migrating osx crosstool.
   * TODO(b/37271982): Remove after blaze with ar action_config release
   */
  public static final String USES_ACTION_CONFIG_FOR_AR_VARIABLE =
      "uses_action_configs_for_cc_archiving";

  /**
   *  A build variable whose presence indicates that files were compiled with fission (debug
   *  info is in .dwo files instead of .o files and linker needs to know).
   */
  public static final String IS_USING_FISSION_VARIABLE = "is_using_fission";

  @Deprecated
  public static final String IS_NOT_CC_TEST_LINK_ACTION_VARIABLE = "is_not_cc_test_link_action";

  public static final String SHARED_NONLTO_BACKEND_ROOT_PREFIX = "shared.nonlto";

  // Builder-only
  // Null when invoked from tests (e.g. via createTestBuilder).
  @Nullable private final RuleContext ruleContext;
  private final AnalysisEnvironment analysisEnvironment;
  private final Artifact output;
  private final CppSemantics cppSemantics;
  @Nullable private String mnemonic;

  // can be null for CppLinkAction.createTestBuilder()
  @Nullable private final CcToolchainProvider toolchain;
  private final FdoSupportProvider fdoSupport;
  private Artifact interfaceOutput;
  private Artifact symbolCounts;
  private PathFragment runtimeSolibDir;
  protected final BuildConfiguration configuration;
  private final CppConfiguration cppConfiguration;
  private FeatureConfiguration featureConfiguration;

  // Morally equivalent with {@link Context}, except these are mutable.
  // Keep these in sync with {@link Context}.
  private final Set<LinkerInput> objectFiles = new LinkedHashSet<>();
  private final Set<Artifact> nonCodeInputs = new LinkedHashSet<>();
  private final NestedSetBuilder<LibraryToLink> libraries = NestedSetBuilder.linkOrder();
  private NestedSet<Artifact> crosstoolInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  private Artifact runtimeMiddleman;
  private ArtifactCategory runtimeType = null;
  private NestedSet<Artifact> runtimeInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  private final ImmutableSet.Builder<Linkstamp> linkstampsBuilder = ImmutableSet.builder();
  private ImmutableList<String> additionalLinkstampDefines = ImmutableList.of();
  private final List<String> linkopts = new ArrayList<>();
  private LinkTargetType linkType = LinkTargetType.STATIC_LIBRARY;
  private LinkStaticness linkStaticness = LinkStaticness.FULLY_STATIC;
  private String libraryIdentifier = null;
  private ImmutableMap<Artifact, Artifact> ltoBitcodeFiles;
  private Artifact defFile;

  private boolean fake;
  private boolean isNativeDeps;
  private boolean useTestOnlyFlags;
  private boolean wholeArchive;
  private LinkArtifactFactory linkArtifactFactory = CppLinkAction.DEFAULT_ARTIFACT_FACTORY;

  private boolean isLtoIndexing = false;
  private boolean usePicForLtoBackendActions = false;
  private Iterable<LtoBackendArtifacts> allLtoArtifacts = null;
  
  private final List<VariablesExtension> variablesExtensions = new ArrayList<>();
  private final NestedSetBuilder<Artifact> linkActionInputs = NestedSetBuilder.stableOrder();
  private final ImmutableList.Builder<Artifact> linkActionOutputs = ImmutableList.builder();

  /**
   * Creates a builder that builds {@link CppLinkAction} instances.
   *
   * @param ruleContext the rule that owns the action
   * @param output the output artifact
   * @param toolchain the C++ toolchain provider
   * @param fdoSupport the C++ FDO optimization support
   * @param cppSemantics to be used for linkstamp compiles
   */
  public CppLinkActionBuilder(
      RuleContext ruleContext,
      Artifact output,
      CcToolchainProvider toolchain,
      FdoSupportProvider fdoSupport,
      FeatureConfiguration featureConfiguration,
      CppSemantics cppSemantics) {
    this(
        ruleContext,
        output,
        ruleContext.getConfiguration(),
        ruleContext.getAnalysisEnvironment(),
        toolchain,
        fdoSupport,
        featureConfiguration,
        cppSemantics);
  }

  /**
   * Creates a builder that builds {@link CppLinkAction} instances.
   *
   * @param ruleContext the rule that owns the action
   * @param output the output artifact
   * @param configuration build configuration
   * @param toolchain C++ toolchain provider
   * @param fdoSupport the C++ FDO optimization support
   * @param cppSemantics to be used for linkstamp compiles
   */
  public CppLinkActionBuilder(
      RuleContext ruleContext,
      Artifact output,
      BuildConfiguration configuration,
      CcToolchainProvider toolchain,
      FdoSupportProvider fdoSupport,
      FeatureConfiguration featureConfiguration,
      CppSemantics cppSemantics) {
    this(
        ruleContext,
        output,
        configuration,
        ruleContext.getAnalysisEnvironment(),
        toolchain,
        fdoSupport,
        featureConfiguration,
        cppSemantics);
  }

  /**
   * Creates a builder that builds {@link CppLinkAction}s.
   *
   * @param ruleContext the rule that owns the action
   * @param output the output artifact
   * @param configuration the configuration used to determine the tool chain and the default link
   *     options
   * @param toolchain the C++ toolchain provider
   * @param fdoSupport the C++ FDO optimization support
   * @param cppSemantics to be used for linkstamp compiles
   */
  private CppLinkActionBuilder(
      @Nullable RuleContext ruleContext,
      Artifact output,
      BuildConfiguration configuration,
      AnalysisEnvironment analysisEnvironment,
      CcToolchainProvider toolchain,
      FdoSupportProvider fdoSupport,
      FeatureConfiguration featureConfiguration,
      CppSemantics cppSemantics) {
    this.ruleContext = ruleContext;
    this.analysisEnvironment = Preconditions.checkNotNull(analysisEnvironment);
    this.output = Preconditions.checkNotNull(output);
    this.configuration = Preconditions.checkNotNull(configuration);
    this.cppConfiguration = configuration.getFragment(CppConfiguration.class);
    this.toolchain = toolchain;
    this.fdoSupport = fdoSupport;
    if (toolchain.supportsEmbeddedRuntimes() && toolchain != null) {
      runtimeSolibDir = toolchain.getDynamicRuntimeSolibDir();
    }
    this.featureConfiguration = featureConfiguration;
    this.cppSemantics = Preconditions.checkNotNull(cppSemantics);
  }

  /**
   * Given a Context, creates a Builder that builds {@link CppLinkAction}s. Note well: Keep the
   * Builder->Context and Context->Builder transforms consistent!
   *
   * @param ruleContext the rule that owns the action
   * @param output the output artifact
   * @param linkContext an immutable CppLinkAction.Context from the original builder
   * @param configuration build configuration
   * @param toolchain the C++ toolchain provider
   * @param fdoSupport the C++ FDO optimization support
   * @param cppSemantics to be used for linkstamp compiles
   */
  public CppLinkActionBuilder(
      RuleContext ruleContext,
      Artifact output,
      Context linkContext,
      BuildConfiguration configuration,
      CcToolchainProvider toolchain,
      FdoSupportProvider fdoSupport,
      FeatureConfiguration featureConfiguration,
      CppSemantics cppSemantics) {
    // These Builder-only fields get set in the constructor:
    //   ruleContext, analysisEnvironment, outputPath, configuration, runtimeSolibDir
    this(
        ruleContext,
        output,
        configuration,
        ruleContext.getAnalysisEnvironment(),
        toolchain,
        fdoSupport,
        featureConfiguration,
        cppSemantics);
    Preconditions.checkNotNull(linkContext);

    // All linkContext fields should be transferred to this Builder.
    this.objectFiles.addAll(linkContext.objectFiles);
    this.nonCodeInputs.addAll(linkContext.nonCodeInputs);
    this.libraries.addTransitive(linkContext.libraries);
    this.crosstoolInputs = linkContext.crosstoolInputs;
    this.ltoBitcodeFiles = linkContext.ltoBitcodeFiles;
    this.runtimeMiddleman = linkContext.runtimeMiddleman;
    this.runtimeInputs = linkContext.runtimeInputs;
    this.runtimeType = linkContext.runtimeType;
    this.linkstampsBuilder.addAll(linkContext.linkstamps);
    this.linkopts.addAll(linkContext.linkopts);
    this.linkType = linkContext.linkType;
    this.linkStaticness = linkContext.linkStaticness;
    this.fake = linkContext.fake;
    this.isNativeDeps = linkContext.isNativeDeps;
    this.useTestOnlyFlags = linkContext.useTestOnlyFlags;
  }

  /** Returns the action name for purposes of querying the crosstool. */
  private String getActionName() {
    return linkType.getActionName();
  }
  
  /** Returns linker inputs that are not libraries. */
  public Set<LinkerInput> getObjectFiles() {
    return objectFiles;
  }

  public Set<Artifact> getNonCodeInputs() {
    return nonCodeInputs;
  }

  /**
   * Returns linker inputs that are libraries.
   */
  public NestedSetBuilder<LibraryToLink> getLibraries() {
    return libraries;
  }

  /**
   * Returns inputs arising from the crosstool.
   */
  public NestedSet<Artifact> getCrosstoolInputs() {
    return this.crosstoolInputs;
  }
  
  /**
   * Returns the runtime middleman artifact.
   */
  public Artifact getRuntimeMiddleman() {
    return this.runtimeMiddleman;
  }
  
  /**
   * Returns runtime inputs for this link action.
   */
  public NestedSet<Artifact> getRuntimeInputs() {
    return this.runtimeInputs;
  }

  public ArtifactCategory getRuntimeType() {
    return runtimeType;
  }

  /** Returns linkstamps for this link action. */
  public final ImmutableSet<Linkstamp> getLinkstamps() {
    return linkstampsBuilder.build();
  }

  /**
   * Returns command line options for this link action.
   */
  public final List<String> getLinkopts() {
    return this.linkopts;
  }
  
  /**
   * Returns the type of this link action.
   */
  public LinkTargetType getLinkType() {
    return this.linkType;
  }
  /**
   * Returns the staticness of this link action.
   */
  public LinkStaticness getLinkStaticness() {
    return this.linkStaticness;
  }
  /**
   * Returns linker inputs that are lto bitcode files in a map from the full bitcode file used by
   * the LTO Backend to the minimized bitcode used by the LTO indexing.
   */
  public ImmutableMap<Artifact, Artifact> getLtoBitcodeFiles() {
    return this.ltoBitcodeFiles;
  }

  /**
   * Returns true for a cc_fake_binary.
   */
  public boolean isFake() {
    return this.fake;
  }
  
  /**
   * Returns true for native dependencies of another language.
   */
  public boolean isNativeDeps() {
    return this.isNativeDeps;
  }
 
  public CppLinkActionBuilder setLinkArtifactFactory(LinkArtifactFactory linkArtifactFactory) {
    this.linkArtifactFactory = linkArtifactFactory;
    return this;
  }
  
  /**
   * Returns true if this link action uses test only flags.
   */
  public boolean useTestOnlyFlags() {
    return this.useTestOnlyFlags;
  }

  /**
   * Maps bitcode object files used by the LTO backends to the corresponding minimized bitcode file
   * used as input to the LTO indexing step.
   */
  private ImmutableSet<LinkerInput> computeLtoIndexingObjectFileInputs() {
    ImmutableSet.Builder<LinkerInput> objectFileInputsBuilder = ImmutableSet.builder();
    for (LinkerInput input : objectFiles) {
      Artifact objectFile = input.getArtifact();
      objectFileInputsBuilder.add(
          LinkerInputs.simpleLinkerInput(
              this.ltoBitcodeFiles.getOrDefault(objectFile, objectFile),
              ArtifactCategory.OBJECT_FILE));
    }
    return objectFileInputsBuilder.build();
  }

  /**
   * Maps bitcode library files used by the LTO backends to the corresponding minimized bitcode file
   * used as input to the LTO indexing step.
   */
  private static NestedSet<LibraryToLink> computeLtoIndexingUniqueLibraries(
      NestedSet<LibraryToLink> originalUniqueLibraries) {
    NestedSetBuilder<LibraryToLink> uniqueLibrariesBuilder = NestedSetBuilder.linkOrder();
    for (LibraryToLink lib : originalUniqueLibraries) {
      if (!lib.containsObjectFiles()) {
        uniqueLibrariesBuilder.add(lib);
        continue;
      }
      ImmutableSet.Builder<Artifact> newObjectFilesBuilder = ImmutableSet.builder();
      for (Artifact a : lib.getObjectFiles()) {
        newObjectFilesBuilder.add(lib.getLtoBitcodeFiles().getOrDefault(a, a));
      }
      uniqueLibrariesBuilder.add(
          LinkerInputs.newInputLibrary(
              lib.getArtifact(),
              lib.getArtifactCategory(),
              lib.getLibraryIdentifier(),
              newObjectFilesBuilder.build(),
              lib.getLtoBitcodeFiles(),
              /* sharedNonLtoBackends= */ null));
    }
    return uniqueLibrariesBuilder.build();
  }

  /**
   * Returns true if there are any LTO bitcode inputs to this link, either directly transitively via
   * library inputs.
   */
  public boolean hasLtoBitcodeInputs() {
    if (!ltoBitcodeFiles.isEmpty()) {
      return true;
    }
    for (LibraryToLink lib : libraries.build()) {
      if (!lib.getLtoBitcodeFiles().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * An implementation of {@link
   * com.google.devtools.build.lib.rules.cpp.CppLinkAction.LinkArtifactFactory} that can create
   * artifacts anywhere.
   *
   * <p>Necessary when the LTO backend actions of libraries should be shareable, and thus cannot be
   * under the package directory.
   */
  private static final CppLinkAction.LinkArtifactFactory SHAREABLE_LINK_ARTIFACT_FACTORY =
      new CppLinkAction.LinkArtifactFactory() {
        @Override
        public Artifact create(
            RuleContext ruleContext,
            BuildConfiguration configuration,
            PathFragment rootRelativePath) {
          return ruleContext.getShareableArtifact(
              rootRelativePath,
              configuration.getBinDirectory(ruleContext.getRule().getRepository()));
        }
      };

  /*
   * Create an LtoBackendArtifacts object, using the appropriate constructor depending on whether
   * the associated ThinLTO link will utilize LTO indexing (therefore unique LTO backend actions),
   * or not (and therefore the library being linked will create a set of shared LTO backends).
   */
  private LtoBackendArtifacts createLtoArtifact(
      Artifact bitcodeFile,
      Map<PathFragment, Artifact> allBitcode,
      PathFragment ltoOutputRootPrefix,
      boolean createSharedNonLto,
      List<String> argv) {
    // Depending on whether LTO indexing is allowed, generate an LTO backend
    // that will be fed the results of the indexing step, or a dummy LTO backend
    // that simply compiles the bitcode into native code without any index-based
    // cross module optimization.
    LtoBackendArtifacts ltoArtifact =
        createSharedNonLto
            ? new LtoBackendArtifacts(
                ltoOutputRootPrefix,
                bitcodeFile,
                ruleContext,
                configuration,
                SHAREABLE_LINK_ARTIFACT_FACTORY,
                featureConfiguration,
                toolchain,
                fdoSupport,
                usePicForLtoBackendActions,
                CppHelper.useFission(cppConfiguration, toolchain),
                argv)
            : new LtoBackendArtifacts(
                ltoOutputRootPrefix,
                bitcodeFile,
                allBitcode,
                ruleContext,
                configuration,
                linkArtifactFactory,
                featureConfiguration,
                toolchain,
                fdoSupport,
                usePicForLtoBackendActions,
                CppHelper.useFission(cppConfiguration, toolchain),
                argv);
    return ltoArtifact;
  }

  private List<String> getLtoBackendCommandLineOptions(ImmutableSet<String> features) {
    List<String> argv = new ArrayList<>();
    argv.addAll(toolchain.getLinkOptions());
    argv.addAll(CppHelper.getCompilerOptions(cppConfiguration, toolchain, features));
    return argv;
  }

  private Iterable<LtoBackendArtifacts> createLtoArtifacts(
      PathFragment ltoOutputRootPrefix,
      NestedSet<LibraryToLink> uniqueLibraries,
      boolean allowLtoIndexing,
      ImmutableSet<String> features) {
    Set<Artifact> compiled = new LinkedHashSet<>();
    for (LibraryToLink lib : uniqueLibraries) {
      compiled.addAll(lib.getLtoBitcodeFiles().keySet());
    }

    // This flattens the set of object files, so for M binaries and N .o files,
    // this is O(M*N). If we had a nested set of .o files, we could have O(M + N) instead.
    Map<PathFragment, Artifact> allBitcode = new HashMap<>();
    for (LibraryToLink lib : uniqueLibraries) {
      if (!lib.containsObjectFiles()) {
        continue;
      }
      for (Artifact objectFile : lib.getObjectFiles()) {
        if (compiled.contains(objectFile)) {
          allBitcode.put(objectFile.getExecPath(), objectFile);
        }
      }
    }
    for (LinkerInput input : objectFiles) {
      if (this.ltoBitcodeFiles.containsKey(input.getArtifact())) {
        allBitcode.put(input.getArtifact().getExecPath(), input.getArtifact());
      }
    }

    List<String> argv = getLtoBackendCommandLineOptions(features);
    ImmutableList.Builder<LtoBackendArtifacts> ltoOutputs = ImmutableList.builder();
    for (LibraryToLink lib : uniqueLibraries) {
      if (!lib.containsObjectFiles()) {
        continue;
      }
      // We will create new LTO backends whenever we are performing LTO indexing, in which case
      // each target linking this library needs a unique set of LTO backends.
      for (Artifact objectFile : lib.getObjectFiles()) {
        if (compiled.contains(objectFile)) {
          if (allowLtoIndexing) {
            LtoBackendArtifacts ltoArtifacts =
                createLtoArtifact(
                    objectFile,
                    allBitcode,
                    ltoOutputRootPrefix,
                    /* createSharedNonLto= */ false,
                    argv);
            ltoOutputs.add(ltoArtifacts);
          } else {
            // We should have created shared LTO backends when the library was created.
            Preconditions.checkNotNull(lib.getSharedNonLtoBackends());
            LtoBackendArtifacts ltoArtifacts =
                lib.getSharedNonLtoBackends().getOrDefault(objectFile, null);
            Preconditions.checkNotNull(ltoArtifacts);
            ltoOutputs.add(ltoArtifacts);
          }
        }
      }
    }
    for (LinkerInput input : objectFiles) {
      if (this.ltoBitcodeFiles.containsKey(input.getArtifact())) {
        LtoBackendArtifacts ltoArtifacts =
            createLtoArtifact(
                input.getArtifact(), allBitcode, ltoOutputRootPrefix, !allowLtoIndexing, argv);
        ltoOutputs.add(ltoArtifacts);
      }
    }

    return ltoOutputs.build();
  }

  private ImmutableMap<Artifact, LtoBackendArtifacts> createSharedNonLtoArtifacts(
      ImmutableSet<String> features, boolean isLtoIndexing) {
    // Only create the shared LTO artifacts for a statically linked library that has bitcode files.
    if (ltoBitcodeFiles == null || isLtoIndexing || linkType.staticness() != Staticness.STATIC) {
      return ImmutableMap.<Artifact, LtoBackendArtifacts>of();
    }

    PathFragment ltoOutputRootPrefix = PathFragment.create(SHARED_NONLTO_BACKEND_ROOT_PREFIX);

    List<String> argv = getLtoBackendCommandLineOptions(features);

    ImmutableMap.Builder<Artifact, LtoBackendArtifacts> sharedNonLtoBackends =
        ImmutableMap.builder();

    for (LinkerInput input : objectFiles) {
      if (this.ltoBitcodeFiles.containsKey(input.getArtifact())) {
        LtoBackendArtifacts ltoArtifacts =
            createLtoArtifact(
                input.getArtifact(),
                /* allBitcode= */ null,
                ltoOutputRootPrefix,
                /* createSharedNonLto= */ true,
                argv);
        sharedNonLtoBackends.put(input.getArtifact(), ltoArtifacts);
      }
    }

    return sharedNonLtoBackends.build();
  }

  @VisibleForTesting
  boolean canSplitCommandLine() {
    if (fake) {
      return false;
    }

    if (toolchain == null || !toolchain.supportsParamFiles()) {
      return false;
    }

    switch (linkType) {
        // On Unix, we currently can't split dynamic library links if they have interface outputs.
        // That was probably an unintended side effect of the change that introduced interface
        // outputs.
        // On Windows, We can always split the command line when building DLL.
      case DYNAMIC_LIBRARY:
        return (interfaceOutput == null
            || featureConfiguration.isEnabled(CppRuleClasses.TARGETS_WINDOWS));
      case EXECUTABLE:
      case STATIC_LIBRARY:
      case PIC_STATIC_LIBRARY:
      case ALWAYS_LINK_STATIC_LIBRARY:
      case ALWAYS_LINK_PIC_STATIC_LIBRARY:
        return true;

      default:
        return false;
    }
  }

  /** Builds the Action as configured and returns it. */
  public CppLinkAction build() throws InterruptedException {
    // Executable links do not have library identifiers.
    boolean hasIdentifier = (libraryIdentifier != null);
    boolean isExecutable = linkType.isExecutable();
    Preconditions.checkState(hasIdentifier != isExecutable);
    Preconditions.checkNotNull(featureConfiguration);
    ImmutableSet<Linkstamp> linkstamps = linkstampsBuilder.build();
    final ImmutableMap<Linkstamp, Artifact> linkstampMap =
        mapLinkstampsToOutputs(linkstamps, ruleContext, configuration, output, linkArtifactFactory);

    if (interfaceOutput != null && (fake || linkType != LinkTargetType.DYNAMIC_LIBRARY)) {
      throw new RuntimeException(
          "Interface output can only be used " + "with non-fake DYNAMIC_LIBRARY targets");
    }

    if (!featureConfiguration.actionIsConfigured(linkType.getActionName())) {
      ruleContext.ruleError(
          String.format(
              "Expected action_config for '%s' to be configured", linkType.getActionName()));
    }

    final ImmutableList<Artifact> buildInfoHeaderArtifacts =
        !linkstamps.isEmpty()
            ? analysisEnvironment.getBuildInfo(ruleContext, CppBuildInfo.KEY, configuration)
            : ImmutableList.of();

    boolean needWholeArchive =
        wholeArchive
            || needWholeArchive(linkStaticness, linkType, linkopts, isNativeDeps, cppConfiguration);
    // Disallow LTO indexing for tests that link statically. Otherwise this will provoke
    // Blaze OOM errors in the case where multiple static tests are invoked together,
    // since each target needs a separate set of LTO Backend actions. With dynamic linking,
    // the targest share the dynamic libraries which were produced via smaller subsets of
    // LTO indexing/backends. ThinLTO on the tests will be different than the ThinLTO
    // optimizations applied to the associated main binaries anyway.
    boolean allowLtoIndexing =
        linkStaticness == LinkStaticness.DYNAMIC
            || !ruleContext.isTestTarget()
            || !featureConfiguration.isEnabled(
                CppRuleClasses.THIN_LTO_LINKSTATIC_TESTS_USE_SHARED_NONLTO_BACKENDS);

    // ruleContext can only be null during testing. This is kind of ugly.
    final ImmutableSet<String> features =
        (ruleContext == null) ? ImmutableSet.of() : ruleContext.getFeatures();

    NestedSet<LibraryToLink> originalUniqueLibraries = libraries.build();

    PathFragment ltoOutputRootPrefix = null;
    if (isLtoIndexing) {
      Preconditions.checkState(allLtoArtifacts == null);
      ltoOutputRootPrefix =
          allowLtoIndexing
              ? FileSystemUtils.appendExtension(output.getRootRelativePath(), ".lto")
              : PathFragment.create(SHARED_NONLTO_BACKEND_ROOT_PREFIX);
      // Use the originalUniqueLibraries which contains the full bitcode files
      // needed by the LTO backends (as opposed to the minimized bitcode files
      // containing just the summaries and symbol information that can be used by
      // the LTO indexing step).
      allLtoArtifacts =
          createLtoArtifacts(
              ltoOutputRootPrefix, originalUniqueLibraries, allowLtoIndexing, features);

      if (!allowLtoIndexing) {
        return null;
      }
    }

    // Get the set of object files and libraries containing the correct
    // inputs for this link, depending on whether this is LTO indexing or
    // a native link.
    NestedSet<LibraryToLink> uniqueLibraries;
    ImmutableSet<LinkerInput> objectFileInputs;
    if (isLtoIndexing) {
      objectFileInputs = computeLtoIndexingObjectFileInputs();
      uniqueLibraries = computeLtoIndexingUniqueLibraries(originalUniqueLibraries);
    } else {
      ImmutableSet.Builder<LinkerInput> builder =
          ImmutableSet.<LinkerInput>builder().addAll(objectFiles);
      builder.addAll(
          LinkerInputs.simpleLinkerInputs(linkstampMap.values(), ArtifactCategory.OBJECT_FILE));

      objectFileInputs = builder.build();
      uniqueLibraries = originalUniqueLibraries;
    }
    final Iterable<Artifact> objectArtifacts = LinkerInputs.toLibraryArtifacts(objectFileInputs);

    final Iterable<LinkerInput> linkerInputs =
        IterablesChain.<LinkerInput>builder()
            .add(objectFileInputs)
            .add(
                ImmutableIterable.from(
                    Link.mergeInputsCmdLine(
                        uniqueLibraries,
                        needWholeArchive,
                        CppHelper.getArchiveType(cppConfiguration, toolchain))))
            .build();

    final LibraryToLink outputLibrary =
        linkType.isExecutable()
            ? null
            : LinkerInputs.newInputLibrary(
                output,
                linkType.getLinkerOutput(),
                libraryIdentifier,
                objectArtifacts,
                ltoBitcodeFiles,
                createSharedNonLtoArtifacts(features, isLtoIndexing));
    final LibraryToLink interfaceOutputLibrary =
        (interfaceOutput == null)
            ? null
            : LinkerInputs.newInputLibrary(
                interfaceOutput,
                ArtifactCategory.DYNAMIC_LIBRARY,
                libraryIdentifier,
                objectArtifacts,
                ltoBitcodeFiles,
                /* sharedNonLtoBackends= */ null);

    @Nullable Artifact thinltoParamFile = null;
    if (allowLtoIndexing && allLtoArtifacts != null) {
      // Create artifact for the file that the LTO indexing step will emit
      // object file names into for any that were included in the link as
      // determined by the linker's symbol resolution. It will be used to
      // provide the inputs for the subsequent final native object link.
      // Note that the paths emitted into this file will have their prefixes
      // replaced with the final output directory, so they will be the paths
      // of the native object files not the input bitcode files.
      PathFragment linkerParamFileRootPath =
          ParameterFile.derivePath(output.getRootRelativePath(), "lto-final");
      thinltoParamFile =
          linkArtifactFactory.create(ruleContext, configuration, linkerParamFileRootPath);
    }

    final ImmutableList<Artifact> actionOutputs;
    if (isLtoIndexing) {
      ImmutableList.Builder<Artifact> builder = ImmutableList.builder();
      for (LtoBackendArtifacts ltoA : allLtoArtifacts) {
        ltoA.addIndexingOutputs(builder);
      }
      if (thinltoParamFile != null) {
        builder.add(thinltoParamFile);
      }
      actionOutputs = builder.build();
    } else {
      actionOutputs =
          constructOutputs(
              output,
              linkActionOutputs.build(),
              interfaceOutputLibrary == null ? null : interfaceOutputLibrary.getArtifact(),
              symbolCounts);
    }

    ImmutableList<LinkerInput> runtimeLinkerInputs =
        ImmutableList.copyOf(LinkerInputs.simpleLinkerInputs(runtimeInputs, runtimeType));

    PathFragment paramRootPath =
        ParameterFile.derivePath(output.getRootRelativePath(), (isLtoIndexing) ? "lto-index" : "2");

    @Nullable
    final Artifact paramFile =
        canSplitCommandLine()
            ? linkArtifactFactory.create(ruleContext, configuration, paramRootPath)
            : null;

    // Add build variables necessary to template link args into the crosstool.
    Variables.Builder buildVariablesBuilder = new Variables.Builder(toolchain.getBuildVariables());
    Preconditions.checkState(!isLtoIndexing || allowLtoIndexing);
    CppLinkVariablesExtension variablesExtension =
        isLtoIndexing
            ? new CppLinkVariablesExtension(
                configuration,
                needWholeArchive,
                linkerInputs,
                runtimeLinkerInputs,
                /* output= */ null,
                paramFile,
                thinltoParamFile,
                ltoOutputRootPrefix,
                // If we reached here, then allowLtoIndexing must be true (checked above).
                /* allowLtoIndexing= */ true,
                /* interfaceLibraryBuilder= */ null,
                /* interfaceLibraryOutput= */ null)
            : new CppLinkVariablesExtension(
                configuration,
                needWholeArchive,
                linkerInputs,
                runtimeLinkerInputs,
                output,
                paramFile,
                allowLtoIndexing ? thinltoParamFile : null,
                /* ltoOutputRootPrefix= */ PathFragment.EMPTY_FRAGMENT,
                allowLtoIndexing,
                toolchain.getInterfaceSoBuilder(),
                interfaceOutput);
    variablesExtension.addVariables(buildVariablesBuilder);
    for (VariablesExtension extraVariablesExtension : variablesExtensions) {
      extraVariablesExtension.addVariables(buildVariablesBuilder);
    }
    Variables buildVariables = buildVariablesBuilder.build();

    Preconditions.checkArgument(
        linkType != LinkTargetType.INTERFACE_DYNAMIC_LIBRARY,
        "you can't link an interface dynamic library directly");
    if (linkType != LinkTargetType.DYNAMIC_LIBRARY) {
      Preconditions.checkArgument(
          interfaceOutput == null,
          "interface output may only be non-null for dynamic library links");
    }
    if (linkType.staticness() == Staticness.STATIC) {
      // solib dir must be null for static links
      runtimeSolibDir = null;

      Preconditions.checkArgument(
          linkStaticness == LinkStaticness.FULLY_STATIC, "static library link must be static");
      Preconditions.checkArgument(
          symbolCounts == null, "the symbol counts output must be null for static links");
      Preconditions.checkArgument(
          !isNativeDeps, "the native deps flag must be false for static links");
      Preconditions.checkArgument(
          !needWholeArchive, "the need whole archive flag must be false for static links");
    }

    LinkCommandLine.Builder linkCommandLineBuilder =
        new LinkCommandLine.Builder(configuration, ruleContext)
            .setLinkerInputs(linkerInputs)
            .setRuntimeInputs(runtimeLinkerInputs)
            .setLinkTargetType(linkType)
            .setLinkStaticness(linkStaticness)
            .setFeatures(features)
            .setRuntimeSolibDir(linkType.staticness() == Staticness.STATIC ? null : runtimeSolibDir)
            .setNativeDeps(isNativeDeps)
            .setUseTestOnlyFlags(useTestOnlyFlags)
            .setParamFile(paramFile)
            .setToolchain(toolchain)
            .setBuildVariables(buildVariables)
            .setFeatureConfiguration(featureConfiguration);

    // TODO(b/62693279): Cleanup once internal crosstools specify ifso building correctly.
    if (shouldUseLinkDynamicLibraryTool()) {
      linkCommandLineBuilder.forceToolPath(
          toolchain.getLinkDynamicLibraryTool().getExecPathString());
    }

    if (!isLtoIndexing) {
      linkCommandLineBuilder
          .setBuildInfoHeaderArtifacts(buildInfoHeaderArtifacts)
          .setLinkopts(ImmutableList.copyOf(linkopts));
    } else {
      List<String> opts = new ArrayList<>(linkopts);
      opts.addAll(featureConfiguration.getCommandLine("lto-indexing", buildVariables));
      opts.addAll(cppConfiguration.getLtoIndexOptions());
      linkCommandLineBuilder.setLinkopts(ImmutableList.copyOf(opts));
    }

    LinkCommandLine linkCommandLine = linkCommandLineBuilder.build();

    for (Entry<Linkstamp, Artifact> linkstampEntry : linkstampMap.entrySet()) {
      analysisEnvironment.registerAction(
          CppLinkstampCompileHelper.createLinkstampCompileAction(
              ruleContext,
              linkstampEntry.getKey().getArtifact(),
              linkstampEntry.getValue(),
              linkstampEntry.getKey().getDeclaredIncludeSrcs(),
              ImmutableSet.copyOf(nonCodeInputs),
              buildInfoHeaderArtifacts,
              additionalLinkstampDefines,
              toolchain,
              configuration.isCodeCoverageEnabled(),
              cppConfiguration,
              CppHelper.getFdoBuildStamp(ruleContext, fdoSupport.getFdoSupport()),
              featureConfiguration,
              linkType == LinkTargetType.DYNAMIC_LIBRARY && toolchain.toolchainNeedsPic(),
              Matcher.quoteReplacement(
                  isNativeDeps && cppConfiguration.shareNativeDeps()
                      ? output.getExecPathString()
                      : Label.print(getOwner().getLabel())),
              Matcher.quoteReplacement(output.getExecPathString()),
              cppSemantics));
    }

    // Compute the set of inputs - we only need stable order here.
    NestedSetBuilder<Artifact> dependencyInputsBuilder = NestedSetBuilder.stableOrder();
    dependencyInputsBuilder.addTransitive(crosstoolInputs);
    dependencyInputsBuilder.addTransitive(linkActionInputs.build());
    // TODO(b/62693279): Cleanup once internal crosstools specify ifso building correctly.
    if (shouldUseLinkDynamicLibraryTool()) {
      dependencyInputsBuilder.add(toolchain.getLinkDynamicLibraryTool());
    }
    if (runtimeMiddleman != null) {
      dependencyInputsBuilder.add(runtimeMiddleman);
    }
    if (!isLtoIndexing) {
      dependencyInputsBuilder.addAll(linkstampMap.values());
    }
    if (defFile != null) {
      dependencyInputsBuilder.add(defFile);
    }

    Iterable<Artifact> expandedInputs =
        LinkerInputs.toLibraryArtifacts(
            Link.mergeInputsDependencies(
                uniqueLibraries,
                needWholeArchive,
                CppHelper.getArchiveType(cppConfiguration, toolchain)));
    Iterable<Artifact> expandedNonLibraryInputs = LinkerInputs.toLibraryArtifacts(objectFileInputs);

    if (!isLtoIndexing && allLtoArtifacts != null) {
      // We are doing LTO, and this is the real link, so substitute
      // the LTO bitcode files with the real object files they were translated into.
      Map<Artifact, Artifact> ltoMapping = new HashMap<>();
      for (LtoBackendArtifacts a : allLtoArtifacts) {
        ltoMapping.put(a.getBitcodeFile(), a.getObjectFile());
      }

      // Handle libraries.
      List<Artifact> renamedInputs = new ArrayList<>();
      for (Artifact a : expandedInputs) {
        Artifact renamed = ltoMapping.get(a);
        renamedInputs.add(renamed == null ? a : renamed);
      }
      expandedInputs = renamedInputs;

      // Handle non-libraries.
      List<Artifact> renamedNonLibraryInputs = new ArrayList<>();
      for (Artifact a : expandedNonLibraryInputs) {
        Artifact renamed = ltoMapping.get(a);
        renamedNonLibraryInputs.add(renamed == null ? a : renamed);
      }
      expandedNonLibraryInputs = renamedNonLibraryInputs;
    }

    // getPrimaryInput returns the first element, and that is a public interface - therefore the
    // order here is important.
    IterablesChain.Builder<Artifact> inputsBuilder =
        IterablesChain.<Artifact>builder()
            .add(ImmutableList.copyOf(expandedNonLibraryInputs))
            .add(ImmutableList.copyOf(nonCodeInputs))
            .add(dependencyInputsBuilder.build())
            .add(ImmutableIterable.from(expandedInputs));

    if (thinltoParamFile != null && !isLtoIndexing) {
      inputsBuilder.add(ImmutableList.of(thinltoParamFile));
    }
    if (linkCommandLine.getParamFile() != null) {
      inputsBuilder.add(ImmutableList.of(linkCommandLine.getParamFile()));
      Action parameterFileWriteAction =
          new ParameterFileWriteAction(
              getOwner(),
              paramFile,
              linkCommandLine.paramCmdLine(),
              ParameterFile.ParameterFileType.UNQUOTED,
              ISO_8859_1);
      analysisEnvironment.registerAction(parameterFileWriteAction);
    }

    ImmutableMap<String, String> toolchainEnv =
        featureConfiguration.getEnvironmentVariables(getActionName(), buildVariables);

    // If the crosstool uses action_configs to configure cc compilation, collect execution info
    // from there, otherwise, use no execution info.
    // TODO(b/27903698): Assert that the crosstool has an action_config for this action.
    ImmutableSet.Builder<String> executionRequirements = ImmutableSet.builder();
    if (featureConfiguration.actionIsConfigured(getActionName())) {
      executionRequirements.addAll(
          featureConfiguration.getToolForAction(getActionName()).getExecutionRequirements());
    }

    return new CppLinkAction(
        getOwner(),
        mnemonic,
        inputsBuilder.deduplicate().build(),
        actionOutputs,
        cppConfiguration,
        outputLibrary,
        output,
        interfaceOutputLibrary,
        fake,
        isLtoIndexing,
        linkstampMap
            .keySet()
            .stream()
            .map(Linkstamp::getArtifact)
            .collect(ImmutableList.toImmutableList()),
        linkCommandLine,
        configuration.getVariableShellEnvironment(),
        configuration.getLocalShellEnvironment(),
        toolchainEnv,
        executionRequirements.build(),
        toolchain);
  }

  private boolean shouldUseLinkDynamicLibraryTool() {
    return linkType.equals(LinkTargetType.DYNAMIC_LIBRARY)
        && toolchain.supportsInterfaceSharedObjects()
        && !featureConfiguration.hasConfiguredLinkerPathInActionConfig();
  }

  /** The default heuristic on whether we need to use whole-archive for the link. */
  private static boolean needWholeArchive(
      LinkStaticness staticness,
      LinkTargetType type,
      Collection<String> linkopts,
      boolean isNativeDeps,
      CppConfiguration cppConfig) {
    boolean fullyStatic = (staticness == LinkStaticness.FULLY_STATIC);
    boolean mostlyStatic = (staticness == LinkStaticness.MOSTLY_STATIC);
    boolean sharedLinkopts =
        type == LinkTargetType.DYNAMIC_LIBRARY
            || linkopts.contains("-shared")
            || cppConfig.hasSharedLinkOption();
    return (isNativeDeps || cppConfig.legacyWholeArchive())
        && (fullyStatic || mostlyStatic)
        && sharedLinkopts;
  }

  private static ImmutableList<Artifact> constructOutputs(
      Artifact primaryOutput, Iterable<Artifact> outputList, Artifact... outputs) {
    return new ImmutableList.Builder<Artifact>()
        .add(primaryOutput)
        .addAll(outputList)
        .addAll(CollectionUtils.asListWithoutNulls(outputs))
        .build();
  }

  /**
   * Translates a collection of {@link Linkstamp} instances to an immutable mapping from linkstamp
   * to object files. In other words, given a set of source files, this method determines the output
   * path to which each file should be compiled.
   *
   * @param linkstamps set of {@link Linkstamp}s
   * @param ruleContext the rule for which this link is being performed
   * @param outputBinary the binary output path for this link
   * @return an immutable map that pairs each source file with the corresponding object file that
   *     should be fed into the link
   */
  public static ImmutableMap<Linkstamp, Artifact> mapLinkstampsToOutputs(
      ImmutableSet<Linkstamp> linkstamps,
      RuleContext ruleContext,
      BuildConfiguration configuration,
      Artifact outputBinary,
      LinkArtifactFactory linkArtifactFactory) {
    ImmutableMap.Builder<Linkstamp, Artifact> mapBuilder = ImmutableMap.builder();

    PathFragment outputBinaryPath = outputBinary.getRootRelativePath();
    PathFragment stampOutputDirectory =
        outputBinaryPath
            .getParentDirectory()
            .getRelative(CppHelper.OBJS)
            .getRelative(outputBinaryPath.getBaseName());

    for (Linkstamp linkstamp : linkstamps) {
      PathFragment stampOutputPath =
          stampOutputDirectory.getRelative(
              FileSystemUtils.replaceExtension(
                  linkstamp.getArtifact().getRootRelativePath(), ".o"));
      mapBuilder.put(
          linkstamp,
          // Note that link stamp actions can be shared between link actions that output shared
          // native dep libraries.
          linkArtifactFactory.create(ruleContext, configuration, stampOutputPath));
    }
    return mapBuilder.build();
  }

  protected ActionOwner getOwner() {
    return ruleContext.getActionOwner();
  }
  
  /** Sets the mnemonic for the link action. */
  public CppLinkActionBuilder setMnemonic(String mnemonic) {
    this.mnemonic = mnemonic;
    return this;
  }

  /** Set the crosstool inputs required for the action. */
  public CppLinkActionBuilder setCrosstoolInputs(NestedSet<Artifact> inputs) {
    this.crosstoolInputs = inputs;
    return this;
  }

  /** Returns the set of LTO artifacts created during build() */
  public Iterable<LtoBackendArtifacts> getAllLtoBackendArtifacts() {
    return allLtoArtifacts;
  }

  /**
   * This is the LTO indexing step, rather than the real link.
   *
   * <p>When using this, build() will store allLtoArtifacts as a side-effect so the next build()
   * call can emit the real link. Do not call addInput() between the two build() calls.
   */
  public CppLinkActionBuilder setLtoIndexing(boolean ltoIndexing) {
    this.isLtoIndexing = ltoIndexing;
    return this;
  }

  /** Sets flag for using PIC in any scheduled LTO Backend actions. */
  public CppLinkActionBuilder setUsePicForLtoBackendActions(boolean usePic) {
    this.usePicForLtoBackendActions = usePic;
    return this;
  }

  /** Sets the C++ runtime library inputs for the action. */
  public CppLinkActionBuilder setRuntimeInputs(
      ArtifactCategory runtimeType, Artifact middleman, NestedSet<Artifact> inputs) {
    Preconditions.checkArgument((middleman == null) == inputs.isEmpty());
    this.runtimeType = runtimeType;
    this.runtimeMiddleman = middleman;
    this.runtimeInputs = inputs;
    return this;
  }

  /** Adds a variables extension to template the toolchain for this link action. */
  public CppLinkActionBuilder addVariablesExtension(VariablesExtension variablesExtension) {
    this.variablesExtensions.add(variablesExtension);
    return this;
  }

  /** Adds variables extensions to template the toolchain for this link action. */
  public CppLinkActionBuilder addVariablesExtensions(List<VariablesExtension> variablesExtensions) {
    for (VariablesExtension variablesExtension : variablesExtensions) {
      addVariablesExtension(variablesExtension);
    }
     return this;
   }
  
  /**
   * Sets the interface output of the link. A non-null argument can only be provided if the link
   * type is {@code DYNAMIC_LIBRARY} and fake is false.
   */
  public CppLinkActionBuilder setInterfaceOutput(Artifact interfaceOutput) {
    this.interfaceOutput = interfaceOutput;
    return this;
  }

  public CppLinkActionBuilder setSymbolCountsOutput(Artifact symbolCounts) {
    this.symbolCounts = symbolCounts;
    return this;
  }

  private void addObjectFile(LinkerInput input) {
    // We skip file extension checks for TreeArtifacts because they represent directory artifacts
    // without a file extension.
    String name = input.getArtifact().getFilename();
    Preconditions.checkArgument(
        input.getArtifact().isTreeArtifact() || Link.OBJECT_FILETYPES.matches(name), name);
    this.objectFiles.add(input);
  }

  public CppLinkActionBuilder addLtoBitcodeFiles(ImmutableMap<Artifact, Artifact> files) {
    Preconditions.checkState(ltoBitcodeFiles == null);
    ltoBitcodeFiles = files;
    return this;
  }

  public CppLinkActionBuilder setDefFile(Artifact defFile) {
    this.defFile = defFile;
    return this;
  }

  /**
   * Adds a single object file to the set of inputs.
   */
  public CppLinkActionBuilder addObjectFile(Artifact input) {
    addObjectFile(LinkerInputs.simpleLinkerInput(input, ArtifactCategory.OBJECT_FILE));
    return this;
  }

  /**
   * Adds object files to the linker action.
   */
  public CppLinkActionBuilder addObjectFiles(Iterable<Artifact> inputs) {
    for (Artifact input : inputs) {
      addObjectFile(LinkerInputs.simpleLinkerInput(input, ArtifactCategory.OBJECT_FILE));
    }
    return this;
  }

  /**
   * Adds non-code files to the set of inputs. They will not be passed to the linker command line
   * unless that is explicitly modified, too.
   */
  public CppLinkActionBuilder addNonCodeInputs(Iterable<Artifact> inputs) {
    for (Artifact input : inputs) {
      addNonCodeInput(input);
    }

    return this;
  }

  /**
   * Adds a single non-code file to the set of inputs. It will not be passed to the linker command
   * line unless that is explicitly modified, too.
   */
  public CppLinkActionBuilder addNonCodeInput(Artifact input) {
    String basename = input.getFilename();
    Preconditions.checkArgument(!Link.ARCHIVE_LIBRARY_FILETYPES.matches(basename), basename);
    Preconditions.checkArgument(!Link.SHARED_LIBRARY_FILETYPES.matches(basename), basename);
    Preconditions.checkArgument(!Link.OBJECT_FILETYPES.matches(basename), basename);

    this.nonCodeInputs.add(input);
    return this;
  }

  public CppLinkActionBuilder addFakeObjectFiles(Iterable<Artifact> inputs) {
    for (Artifact input : inputs) {
      addObjectFile(LinkerInputs.fakeLinkerInput(input));
    }
    return this;
  }

  private void checkLibrary(LibraryToLink input) {
    String name = input.getArtifact().getFilename();
    Preconditions.checkArgument(
        Link.ARCHIVE_LIBRARY_FILETYPES.matches(name) || Link.SHARED_LIBRARY_FILETYPES.matches(name),
        "'%s' is not a library file",
        input);
  }

  /**
   * Adds a single artifact to the set of inputs. The artifact must be an archive or a shared
   * library. Note that all directly added libraries are implicitly ordered before all nested sets
   * added with {@link #addLibraries}, even if added in the opposite order.
   */
  public CppLinkActionBuilder addLibrary(LibraryToLink input) {
    checkLibrary(input);
    libraries.add(input);
    return this;
  }

  /**
   * Adds multiple artifact to the set of inputs. The artifacts must be archives or shared
   * libraries.
   */
  public CppLinkActionBuilder addLibraries(NestedSet<LibraryToLink> inputs) {
    for (LibraryToLink input : inputs) {
      checkLibrary(input);
    }
    this.libraries.addTransitive(inputs);
    return this;
  }

  /**
   * Sets the type of ELF file to be created (.a, .so, .lo, executable). The default is {@link
   * LinkTargetType#STATIC_LIBRARY}.
   */
  public CppLinkActionBuilder setLinkType(LinkTargetType linkType) {
    this.linkType = linkType;
    return this;
  }

  /**
   * Sets the degree of "staticness" of the link: fully static (static binding of all symbols),
   * mostly static (use dynamic binding only for symbols from glibc), dynamic (use dynamic binding
   * wherever possible). The default is {@link LinkStaticness#FULLY_STATIC}.
   */
  public CppLinkActionBuilder setLinkStaticness(LinkStaticness linkStaticness) {
    this.linkStaticness = linkStaticness;
    return this;
  }

  /**
   * Sets the identifier of the library produced by the action. See
   * {@link LinkerInputs.LibraryToLink#getLibraryIdentifier()}
   */
  public CppLinkActionBuilder setLibraryIdentifier(String libraryIdentifier) {
    this.libraryIdentifier = libraryIdentifier;
    return this;
  }

  /**
   * Adds {@link Linkstamp}s.
   *
   * <p>This is used to embed various values from the build system into binaries to identify their
   * provenance.
   *
   * <p>Linkstamp object files are also automatically added to the inputs of the link action.
   */
  public CppLinkActionBuilder addLinkstamps(Iterable<Linkstamp> linkstamps) {
    this.linkstampsBuilder.addAll(linkstamps);
    return this;
  }

  public CppLinkActionBuilder setAdditionalLinkstampDefines(
      ImmutableList<String> additionalLinkstampDefines) {
    this.additionalLinkstampDefines = Preconditions.checkNotNull(additionalLinkstampDefines);
    return this;
  }

  /** Adds an additional linker option. */
  public CppLinkActionBuilder addLinkopt(String linkopt) {
    this.linkopts.add(linkopt);
    return this;
  }

  /**
   * Adds multiple linker options at once.
   *
   * @see #addLinkopt(String)
   */
  public CppLinkActionBuilder addLinkopts(Collection<String> linkopts) {
    this.linkopts.addAll(linkopts);
    return this;
  }

  /**
   * Merges the given link params into this builder by calling {@link #addLinkopts}, {@link
   * #addLibraries}, and {@link #addLinkstamps}.
   */
  public CppLinkActionBuilder addLinkParams(
      CcLinkParams linkParams, RuleErrorConsumer errorListener) throws InterruptedException {
    addLinkopts(linkParams.flattenedLinkopts());
    addLibraries(linkParams.getLibraries());
    ExtraLinkTimeLibraries extraLinkTimeLibraries = linkParams.getExtraLinkTimeLibraries();
    if (extraLinkTimeLibraries != null) {
      for (ExtraLinkTimeLibrary extraLibrary : extraLinkTimeLibraries.getExtraLibraries()) {
        addLibraries(extraLibrary.buildLibraries(ruleContext));
      }
    }
    CppHelper.checkLinkstampsUnique(errorListener, linkParams);
    addLinkstamps(linkParams.getLinkstamps());
    return this;
  }

  /** Sets whether this link action will be used for a cc_fake_binary; false by default. */
  public CppLinkActionBuilder setFake(boolean fake) {
    this.fake = fake;
    return this;
  }

  /** Sets whether this link action is used for a native dependency library. */
  public CppLinkActionBuilder setNativeDeps(boolean isNativeDeps) {
    this.isNativeDeps = isNativeDeps;
    return this;
  }

  /**
   * Setting this to true overrides the default whole-archive computation and force-enables whole
   * archives for every archive in the link. This is only necessary for linking executable binaries
   * that are supposed to export symbols.
   *
   * <p>Usually, the link action while use whole archives for dynamic libraries that are native deps
   * (or the legacy whole archive flag is enabled), and that are not dynamically linked.
   *
   * <p>(Note that it is possible to build dynamic libraries with cc_binary rules by specifying
   * linkshared = 1, and giving the rule a name that matches the pattern {@code
   * lib&lt;name&gt;.so}.)
   */
  public CppLinkActionBuilder setWholeArchive(boolean wholeArchive) {
    this.wholeArchive = wholeArchive;
    return this;
  }

  /**
   * Sets whether this link action should use test-specific flags (e.g. $EXEC_ORIGIN instead of
   * $ORIGIN for the solib search path or lazy binding); false by default.
   */
  public CppLinkActionBuilder setUseTestOnlyFlags(boolean useTestOnlyFlags) {
    this.useTestOnlyFlags = useTestOnlyFlags;
    return this;
  }

  /**
   * Sets the name of the directory where the solib symlinks for the dynamic runtime libraries live.
   * This is usually automatically set from the cc_toolchain.
   */
  public CppLinkActionBuilder setRuntimeSolibDir(PathFragment runtimeSolibDir) {
    this.runtimeSolibDir = runtimeSolibDir;
    return this;
  }
  
  /**
   * Adds an extra input artifact to the link action.
   */
  public CppLinkActionBuilder addActionInput(Artifact input) {
    this.linkActionInputs.add(input);
    return this;
  }
  
  /**
   * Adds extra input artifacts to the link action.
   */
  public CppLinkActionBuilder addActionInputs(Iterable<Artifact> inputs) {
    this.linkActionInputs.addAll(inputs);
    return this;
  }
  
  /**
   * Adds extra input artifacts to the link actions.
   */
  public CppLinkActionBuilder addTransitiveActionInputs(NestedSet<Artifact> inputs) {
    this.linkActionInputs.addTransitive(inputs);
    return this;
  }

  /** Adds an extra output artifact to the link action. */
  public CppLinkActionBuilder addActionOutput(Artifact output) {
    this.linkActionOutputs.add(output);
    return this;
  }

  private static class LinkArgCollector {
    ImmutableSet<String> runtimeLibrarySearchDirectories;
    ImmutableSet<String> librarySearchDirectories;
    SequenceBuilder librariesToLink;

    public void setRuntimeLibrarySearchDirectories(
        ImmutableSet<String> runtimeLibrarySearchDirectories) {
      this.runtimeLibrarySearchDirectories = runtimeLibrarySearchDirectories;
    }

    public void setLibrariesToLink(SequenceBuilder librariesToLink) {
      this.librariesToLink = librariesToLink;
    }

    public void setLibrarySearchDirectories(ImmutableSet<String> librarySearchDirectories) {
      this.librarySearchDirectories = librarySearchDirectories;
    }

    public ImmutableSet<String> getRuntimeLibrarySearchDirectories() {
      return runtimeLibrarySearchDirectories;
    }

    public SequenceBuilder getLibrariesToLink() {
      return librariesToLink;
    }

    public ImmutableSet<String> getLibrarySearchDirectories() {
      return librarySearchDirectories;
    }

  }

  private class CppLinkVariablesExtension implements VariablesExtension {

    private final BuildConfiguration configuration;
    private final boolean needWholeArchive;
    private final Iterable<LinkerInput> linkerInputs;
    private final ImmutableList<LinkerInput> runtimeLinkerInputs;
    private final Artifact outputArtifact;
    private final Artifact interfaceLibraryBuilder;
    private final Artifact interfaceLibraryOutput;
    private final Artifact paramFile;
    private final Artifact thinltoParamFile;
    private final PathFragment ltoOutputRootPrefix;
    private final boolean allowLtoIndexing;

    private final LinkArgCollector linkArgCollector = new LinkArgCollector();

    public CppLinkVariablesExtension(
        BuildConfiguration configuration,
        boolean needWholeArchive,
        Iterable<LinkerInput> linkerInputs,
        ImmutableList<LinkerInput> runtimeLinkerInputs,
        Artifact output,
        Artifact paramFile,
        Artifact thinltoParamFile,
        PathFragment ltoOutputRootPrefix,
        boolean allowLtoIndexing,
        Artifact interfaceLibraryBuilder,
        Artifact interfaceLibraryOutput) {
      this.configuration = configuration;
      this.needWholeArchive = needWholeArchive;
      this.linkerInputs = linkerInputs;
      this.runtimeLinkerInputs = runtimeLinkerInputs;
      this.outputArtifact = output;
      this.interfaceLibraryBuilder = interfaceLibraryBuilder;
      this.interfaceLibraryOutput = interfaceLibraryOutput;
      this.paramFile = paramFile;
      this.thinltoParamFile = thinltoParamFile;
      this.ltoOutputRootPrefix = ltoOutputRootPrefix;
      this.allowLtoIndexing = allowLtoIndexing;

      addInputFileLinkOptions(linkArgCollector);
    }

    @Override
    public void addVariables(Variables.Builder buildVariables) {

      // symbol counting
      if (symbolCounts != null) {
        buildVariables.addStringVariable(
            SYMBOL_COUNTS_OUTPUT_VARIABLE, symbolCounts.getExecPathString());
      }

      // pic
      if (cppConfiguration.forcePic()) {
        buildVariables.addStringVariable(FORCE_PIC_VARIABLE, "");
      }

      if (cppConfiguration.shouldStripBinaries()) {
        buildVariables.addStringVariable(STRIP_DEBUG_SYMBOLS_VARIABLE, "");
      }

      if (getLinkType().staticness().equals(Staticness.DYNAMIC)
          && CppHelper.useFission(cppConfiguration, toolchain)) {
        buildVariables.addStringVariable(IS_USING_FISSION_VARIABLE, "");
      }

      if (useTestOnlyFlags()) {
        buildVariables.addIntegerVariable(IS_CC_TEST_VARIABLE, 1);
        buildVariables.addStringVariable(IS_CC_TEST_LINK_ACTION_VARIABLE, "");
      } else {
        buildVariables.addIntegerVariable(IS_CC_TEST_VARIABLE, 0);
        buildVariables.addStringVariable(IS_NOT_CC_TEST_LINK_ACTION_VARIABLE, "");
      }

      if (linkArgCollector.getRuntimeLibrarySearchDirectories() != null) {
        buildVariables.addStringSequenceVariable(
            RUNTIME_LIBRARY_SEARCH_DIRECTORIES_VARIABLE,
            linkArgCollector.getRuntimeLibrarySearchDirectories());
      }

      buildVariables.addCustomBuiltVariable(
          LIBRARIES_TO_LINK_VARIABLE, linkArgCollector.getLibrariesToLink());

      buildVariables.addStringSequenceVariable(
          LIBRARY_SEARCH_DIRECTORIES_VARIABLE, linkArgCollector.getLibrarySearchDirectories());

      if (paramFile != null) {
        buildVariables.addStringVariable(LINKER_PARAM_FILE_VARIABLE, paramFile.getExecPathString());
      }

      // output exec path
      if (outputArtifact != null) {
        buildVariables.addStringVariable(
            OUTPUT_EXECPATH_VARIABLE, outputArtifact.getExecPathString());
      }

      if (isLtoIndexing()) {
        if (thinltoParamFile != null) {
          // This is a lto-indexing action and we want it to populate param file.
          buildVariables.addStringVariable(
              THINLTO_INDEXING_PARAM_FILE_VARIABLE, thinltoParamFile.getExecPathString());
          // TODO(b/33846234): Remove once all the relevant crosstools don't depend on the variable.
          buildVariables.addStringVariable(
              "thinlto_optional_params_file", "=" + thinltoParamFile.getExecPathString());
        } else {
          buildVariables.addStringVariable(THINLTO_INDEXING_PARAM_FILE_VARIABLE, "");
          // TODO(b/33846234): Remove once all the relevant crosstools don't depend on the variable.
          buildVariables.addStringVariable("thinlto_optional_params_file", "");
        }
        buildVariables.addStringVariable(
            THINLTO_PREFIX_REPLACE_VARIABLE,
            configuration.getBinDirectory().getExecPathString()
                + ";"
                + configuration.getBinDirectory().getExecPath().getRelative(ltoOutputRootPrefix));
        buildVariables.addStringVariable(
            THINLTO_OBJECT_SUFFIX_REPLACE_VARIABLE,
            Iterables.getOnlyElement(CppFileTypes.LTO_INDEXING_OBJECT_FILE.getExtensions())
                + ";"
                + Iterables.getOnlyElement(CppFileTypes.OBJECT_FILE.getExtensions()));
      } else {
        if (thinltoParamFile != null) {
          // This is a normal link action and we need to use param file created by lto-indexing.
          buildVariables.addStringVariable(
              THINLTO_PARAM_FILE_VARIABLE, thinltoParamFile.getExecPathString());
        }
      }
      boolean shouldGenerateInterfaceLibrary =
          outputArtifact != null
              && interfaceLibraryBuilder != null
              && interfaceLibraryOutput != null;
      buildVariables.addStringVariable(
          GENERATE_INTERFACE_LIBRARY_VARIABLE, shouldGenerateInterfaceLibrary ? "yes" : "no");
      buildVariables.addStringVariable(
          INTERFACE_LIBRARY_BUILDER_VARIABLE,
          shouldGenerateInterfaceLibrary ? interfaceLibraryBuilder.getExecPathString() : "ignored");
      buildVariables.addStringVariable(
          INTERFACE_LIBRARY_INPUT_VARIABLE,
          shouldGenerateInterfaceLibrary ? outputArtifact.getExecPathString() : "ignored");
      buildVariables.addStringVariable(
          INTERFACE_LIBRARY_OUTPUT_VARIABLE,
          shouldGenerateInterfaceLibrary ? interfaceLibraryOutput.getExecPathString() : "ignored");

      if (defFile != null) {
        buildVariables.addStringVariable(DEF_FILE_PATH_VARIABLE, defFile.getExecPathString());
      }

      fdoSupport.getFdoSupport().getLinkOptions(featureConfiguration, buildVariables);
    }

    private boolean isLtoIndexing() {
      return !ltoOutputRootPrefix.equals(PathFragment.EMPTY_FRAGMENT);
    }

    private boolean isSharedNativeLibrary() {
      return isNativeDeps && cppConfiguration.shareNativeDeps();
    }

    /**
     * When linking a shared library fully or mostly static then we need to link in *all* dependent
     * files, not just what the shared library needs for its own code. This is done by wrapping all
     * objects/libraries with -Wl,-whole-archive and -Wl,-no-whole-archive. For this case the
     * globalNeedWholeArchive parameter must be set to true. Otherwise only library objects (.lo)
     * need to be wrapped with -Wl,-whole-archive and -Wl,-no-whole-archive.
     *
     * <p>TODO: Factor out of the bazel binary into build variables for crosstool action_configs.
     */
    private void addInputFileLinkOptions(LinkArgCollector linkArgCollector) {
      ImmutableSet.Builder<String> librarySearchDirectories = ImmutableSet.builder();
      ImmutableSet.Builder<String> runtimeRpathRoots = ImmutableSet.builder();
      ImmutableSet.Builder<String> rpathRootsForExplicitSoDeps = ImmutableSet.builder();

      // List of command line parameters that need to be placed *outside* of
      // --whole-archive ... --no-whole-archive.
      SequenceBuilder librariesToLink = new SequenceBuilder();

      PathFragment solibDir =
          configuration
              .getBinDirectory(ruleContext.getRule().getRepository())
              .getExecPath()
              .getRelative(toolchain.getSolibDirectory());
      String runtimeSolibName = runtimeSolibDir != null ? runtimeSolibDir.getBaseName() : null;
      boolean runtimeRpath =
          runtimeSolibDir != null
              && (linkType == LinkTargetType.DYNAMIC_LIBRARY
                  || (linkType == LinkTargetType.EXECUTABLE
                      && linkStaticness == LinkStaticness.DYNAMIC));

      if (runtimeRpath) {
        if (isNativeDeps) {
          runtimeRpathRoots.add(".");
        }
        runtimeRpathRoots.add(runtimeSolibName + "/");
      }

      String rpathRoot;
      // Calculate the correct relative value for the "-rpath" link option (which sets
      // the search path for finding shared libraries).
      if (isSharedNativeLibrary()) {
        // For shared native libraries, special symlinking is applied to ensure C++
        // runtimes are available under $ORIGIN/_solib_[arch]. So we set the RPATH to find
        // them.
        //
        // Note that we have to do this because $ORIGIN points to different paths for
        // different targets. In other words, blaze-bin/d1/d2/d3/a_shareddeps.so and
        // blaze-bin/d4/b_shareddeps.so have different path depths. The first could
        // reference a standard blaze-bin/_solib_[arch] via $ORIGIN/../../../_solib[arch],
        // and the second could use $ORIGIN/../_solib_[arch]. But since this is a shared
        // artifact, both are symlinks to the same place, so
        // there's no *one* RPATH setting that fits all targets involved in the sharing.
        rpathRoot = toolchain.getSolibDirectory() + "/";
        if (runtimeRpath) {
          runtimeRpathRoots.add("../" + runtimeSolibName + "/");
        }
      } else {
        // For all other links, calculate the relative path from the output file to _solib_[arch]
        // (the directory where all shared libraries are stored, which resides under the blaze-bin
        // directory. In other words, given blaze-bin/my/package/binary, rpathRoot would be
        // "../../_solib_[arch]".
        if (runtimeRpath) {
          runtimeRpathRoots.add(
              Strings.repeat("../", output.getRootRelativePath().segmentCount() - 1)
                  + runtimeSolibName
                  + "/");
        }

        rpathRoot =
            Strings.repeat("../", output.getRootRelativePath().segmentCount() - 1)
                + toolchain.getSolibDirectory()
                + "/";

        if (isNativeDeps) {
          // We also retain the $ORIGIN/ path to solibs that are in _solib_<arch>, as opposed to
          // the package directory)
          if (runtimeRpath) {
            runtimeRpathRoots.add("../" + runtimeSolibName + "/");
          }
        }
      }

      Map<Artifact, Artifact> ltoMap = generateLtoMap();
      boolean includeSolibDir =
          addLinkerInputs(
              librarySearchDirectories,
              rpathRootsForExplicitSoDeps,
              librariesToLink,
              solibDir,
              rpathRoot,
              ltoMap);
      boolean includeRuntimeSolibDir =
          addRuntimeLinkerInputs(
              librarySearchDirectories,
              rpathRootsForExplicitSoDeps,
              librariesToLink,
              solibDir,
              rpathRoot,
              ltoMap);
      Preconditions.checkState(
          ltoMap == null || ltoMap.isEmpty(), "Still have LTO objects left: %s", ltoMap);

      ImmutableSet.Builder<String> runtimeLibrarySearchDirectories = ImmutableSet.builder();
      // rpath ordering matters for performance; first add the one where most libraries are found.
      if (includeSolibDir) {
        runtimeLibrarySearchDirectories.add(rpathRoot);
      }
      runtimeLibrarySearchDirectories.addAll(rpathRootsForExplicitSoDeps.build());
      if (includeRuntimeSolibDir) {
        runtimeLibrarySearchDirectories.addAll(runtimeRpathRoots.build());
      }

      linkArgCollector.setLibrarySearchDirectories(librarySearchDirectories.build());
      linkArgCollector.setRuntimeLibrarySearchDirectories(runtimeLibrarySearchDirectories.build());
      linkArgCollector.setLibrariesToLink(librariesToLink);
    }

    private Map<Artifact, Artifact> generateLtoMap() {
      if (isLtoIndexing || allLtoArtifacts == null) {
        return null;
      }
      // TODO(bazel-team): The LTO final link can only work if there are individual .o files on
      // the command line. Rather than crashing, this should issue a nice error. We will get
      // this by
      // 1) moving supports_start_end_lib to a toolchain feature
      // 2) having thin_lto require start_end_lib
      // As a bonus, we can rephrase --nostart_end_lib as --features=-start_end_lib and get rid
      // of a command line option.

      Preconditions.checkState(CppHelper.useStartEndLib(cppConfiguration, toolchain));
      Map<Artifact, Artifact> ltoMap = new HashMap<>();
      for (LtoBackendArtifacts l : allLtoArtifacts) {
        ltoMap.put(l.getBitcodeFile(), l.getObjectFile());
      }
      return ltoMap;
    }

    private boolean addRuntimeLinkerInputs(
        Builder<String> librarySearchDirectories,
        Builder<String> rpathRootsForExplicitSoDeps,
        SequenceBuilder librariesToLink,
        PathFragment solibDir,
        String rpathRoot,
        Map<Artifact, Artifact> ltoMap) {
      boolean includeRuntimeSolibDir = false;
      for (LinkerInput input : runtimeLinkerInputs) {
        if (input.getArtifactCategory() == ArtifactCategory.DYNAMIC_LIBRARY
            || input.getArtifactCategory() == ArtifactCategory.INTERFACE_LIBRARY) {
          PathFragment libDir = input.getArtifact().getExecPath().getParentDirectory();
          if (!featureConfiguration.isEnabled(CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY)) {
            Preconditions.checkState(
                runtimeSolibDir != null && libDir.equals(runtimeSolibDir),
                "Artifact '%s' is not under directory '%s'.",
                input.getArtifact(),
                solibDir);
            includeRuntimeSolibDir = true;
          }
          addDynamicInputLinkOptions(
              input,
              librariesToLink,
              librarySearchDirectories,
              rpathRootsForExplicitSoDeps,
              solibDir,
              rpathRoot);
        } else {
          addStaticInputLinkOptions(input, librariesToLink, true, ltoMap);
        }
      }
      return includeRuntimeSolibDir;
    }

    private boolean addLinkerInputs(
        Builder<String> librarySearchDirectories,
        Builder<String> rpathEntries,
        SequenceBuilder librariesToLink,
        PathFragment solibDir,
        String rpathRoot,
        Map<Artifact, Artifact> ltoMap) {
      boolean includeSolibDir = false;
      for (LinkerInput input : linkerInputs) {
        if (input.getArtifactCategory() == ArtifactCategory.DYNAMIC_LIBRARY
            || input.getArtifactCategory() == ArtifactCategory.INTERFACE_LIBRARY) {
          PathFragment libDir = input.getArtifact().getExecPath().getParentDirectory();
          // When COPY_DYNAMIC_LIBRARIES_TO_BINARY is enabled, dynamic libraries are not symlinked
          // under solibDir, so don't check it and don't include solibDir.
          if (!featureConfiguration.isEnabled(CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY)) {
            Preconditions.checkState(
                libDir.startsWith(solibDir),
                "Artifact '%s' is not under directory '%s'.",
                input.getArtifact(),
                solibDir);
            if (libDir.equals(solibDir)) {
              includeSolibDir = true;
            }
          }
          addDynamicInputLinkOptions(
              input,
              librariesToLink,
              librarySearchDirectories,
              rpathEntries,
              solibDir,
              rpathRoot);
        } else {
          addStaticInputLinkOptions(input, librariesToLink, false, ltoMap);
        }
      }
      return includeSolibDir;
    }

    /**
     * Adds command-line options for a dynamic library input file into options and libOpts.
     *
     * @param librariesToLink - a collection that will be exposed as a build variable.
     */
    private void addDynamicInputLinkOptions(
        LinkerInput input,
        SequenceBuilder librariesToLink,
        ImmutableSet.Builder<String> librarySearchDirectories,
        ImmutableSet.Builder<String> rpathRootsForExplicitSoDeps,
        PathFragment solibDir,
        String rpathRoot) {
      Preconditions.checkState(
          input.getArtifactCategory() == ArtifactCategory.DYNAMIC_LIBRARY
              || input.getArtifactCategory() == ArtifactCategory.INTERFACE_LIBRARY);
      Preconditions.checkState(
          !Link.useStartEndLib(input, CppHelper.getArchiveType(cppConfiguration, toolchain)));

      Artifact inputArtifact = input.getArtifact();
      PathFragment libDir = inputArtifact.getExecPath().getParentDirectory();
      if (!libDir.equals(solibDir)
          && (runtimeSolibDir == null || !runtimeSolibDir.equals(libDir))) {
        String dotdots = "";
        PathFragment commonParent = solibDir;
        while (!libDir.startsWith(commonParent)) {
          dotdots += "../";
          commonParent = commonParent.getParentDirectory();
        }

        rpathRootsForExplicitSoDeps.add(
            rpathRoot + dotdots + libDir.relativeTo(commonParent).getPathString());
      }

      librarySearchDirectories.add(
          inputArtifact.getExecPath().getParentDirectory().getPathString());

      String name = inputArtifact.getFilename();
      if (CppFileTypes.SHARED_LIBRARY.matches(name)) {
        // Use normal shared library resolution rules for shared libraries.
        String libName = name.replaceAll("(^lib|\\.(so|dylib)$)", "");
        librariesToLink.addValue(
            LibraryToLinkValue.forDynamicLibrary(libName));
      } else if (CppFileTypes.VERSIONED_SHARED_LIBRARY.matches(name)) {
        // Versioned shared libraries require the exact library filename, e.g.:
        // -lfoo -> libfoo.so
        // -l:libfoo.so.1 -> libfoo.so.1
        librariesToLink.addValue(
            LibraryToLinkValue.forVersionedDynamicLibrary(name));
      } else {
        // Interface shared objects have a non-standard extension
        // that the linker won't be able to find.  So use the
        // filename directly rather than a -l option.  Since the
        // library has an SONAME attribute, this will work fine.
        librariesToLink.addValue(
            LibraryToLinkValue.forInterfaceLibrary(inputArtifact.getExecPathString()));
      }
    }

    /**
     * Adds command-line options for a static library or non-library input into options.
     *
     * @param librariesToLink - a collection that will be exposed as a build variable.
     * @param ltoMap is a mutable list of exec paths that should be on the command-line, which must
     */
    private void addStaticInputLinkOptions(
        LinkerInput input,
        SequenceBuilder librariesToLink,
        boolean isRuntimeLinkerInput,
        @Nullable Map<Artifact, Artifact> ltoMap) {
      ArtifactCategory artifactCategory = input.getArtifactCategory();
      Preconditions.checkState(artifactCategory != ArtifactCategory.DYNAMIC_LIBRARY);
      // If we had any LTO artifacts, ltoMap whould be non-null. In that case,
      // we should have created a thinltoParamFile which the LTO indexing
      // step will populate with the exec paths that correspond to the LTO
      // artifacts that the linker decided to include based on symbol resolution.
      // Those files will be included directly in the link (and not wrapped
      // in --start-lib/--end-lib) to ensure consistency between the two link
      // steps.
      Preconditions.checkState(ltoMap == null || thinltoParamFile != null || !allowLtoIndexing);

      // start-lib/end-lib library: adds its input object files.
      if (Link.useStartEndLib(input, CppHelper.getArchiveType(cppConfiguration, toolchain))) {
        Iterable<Artifact> archiveMembers = input.getObjectFiles();
        if (!Iterables.isEmpty(archiveMembers)) {
          ImmutableList.Builder<String> nonLtoArchiveMembersBuilder = ImmutableList.builder();
          for (Artifact member : archiveMembers) {
            Artifact a;
            if (ltoMap != null && (a = ltoMap.remove(member)) != null) {
              // When ltoMap is non-null the backend artifact may be missing due to libraries that
              // list .o files explicitly, or generate .o files from assembler.
              if (allowLtoIndexing) {
                // The LTO artifacts that should be included in the final link
                // are listed in the thinltoParamFile, generated by the LTO indexing.
                continue;
              }
              // No LTO indexing step, so use the LTO backend's generated artifact directly
              // instead of the bitcode object.
              member = a;
            }
            nonLtoArchiveMembersBuilder.add(member.getExecPathString());
          }
          ImmutableList<String> nonLtoArchiveMembers = nonLtoArchiveMembersBuilder.build();
          if (!nonLtoArchiveMembers.isEmpty()) {
            boolean inputIsWholeArchive = !isRuntimeLinkerInput && needWholeArchive;
            librariesToLink.addValue(
                LibraryToLinkValue.forObjectFileGroup(nonLtoArchiveMembers, inputIsWholeArchive));
          }
        }
      } else {
        Preconditions.checkArgument(
            artifactCategory.equals(ArtifactCategory.OBJECT_FILE)
                || artifactCategory.equals(ArtifactCategory.STATIC_LIBRARY)
                || artifactCategory.equals(ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY));
        boolean isAlwaysLinkStaticLibrary =
            artifactCategory == ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY;
        boolean inputIsWholeArchive =
            (!isRuntimeLinkerInput && (isAlwaysLinkStaticLibrary || needWholeArchive))
                || (isRuntimeLinkerInput && isAlwaysLinkStaticLibrary && !needWholeArchive);

        Artifact inputArtifact = input.getArtifact();
        Artifact a;
        if (ltoMap != null && (a = ltoMap.remove(inputArtifact)) != null) {
          if (allowLtoIndexing) {
            // The LTO artifacts that should be included in the final link
            // are listed in the thinltoParamFile, generated by the LTO indexing.
            return;
          }
          // No LTO indexing step, so use the LTO backend's generated artifact directly
          // instead of the bitcode object.
          inputArtifact = a;
        }

        String name;
        if (input.isFake()) {
          name = Link.FAKE_OBJECT_PREFIX + inputArtifact.getExecPathString();
        } else {
          name = inputArtifact.getExecPathString();
        }

        librariesToLink.addValue(
            artifactCategory.equals(ArtifactCategory.OBJECT_FILE)
                ? LibraryToLinkValue.forObjectFile(name, inputIsWholeArchive)
                : LibraryToLinkValue.forStaticLibrary(name, inputIsWholeArchive));
      }
    }
  }
}
