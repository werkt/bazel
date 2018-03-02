// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.analysis.config.BuildConfiguration.convertOptionsLabel;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.LabelListConverter;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.List;

/** Command-line options for platform-related configuration. */
@AutoCodec(strategy = AutoCodec.Strategy.PUBLIC_FIELDS)
public class PlatformOptions extends FragmentOptions {
  public static final ObjectCodec<PlatformOptions> CODEC = new PlatformOptions_AutoCodec();

  @Option(
    name = "host_platform",
    oldName = "experimental_host_platform",
    converter = BuildConfiguration.EmptyToNullLabelConverter.class,
    defaultValue = "@bazel_tools//platforms:host_platform",
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    effectTags = {
      OptionEffectTag.AFFECTS_OUTPUTS,
      OptionEffectTag.CHANGES_INPUTS,
      OptionEffectTag.LOADING_AND_ANALYSIS
    },
    help = "The label of a platform rule that describes the host system."
  )
  public Label hostPlatform;

  // TODO(katre): Add execution platforms.

  @Option(
    name = "platforms",
    oldName = "experimental_platforms",
    converter = BuildConfiguration.LabelListConverter.class,
    defaultValue = "@bazel_tools//platforms:target_platform",
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    effectTags = {
      OptionEffectTag.AFFECTS_OUTPUTS,
      OptionEffectTag.CHANGES_INPUTS,
      OptionEffectTag.LOADING_AND_ANALYSIS
    },
    help =
        "The labels of the platform rules describing the target platforms for the current command."
  )
  public List<Label> platforms;

  @Option(
    name = "extra_toolchains",
    converter = LabelListConverter.class,
    defaultValue = "",
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    effectTags = {
      OptionEffectTag.AFFECTS_OUTPUTS,
      OptionEffectTag.CHANGES_INPUTS,
      OptionEffectTag.LOADING_AND_ANALYSIS
    },
    help =
        "The labels of toolchain rules to be considered during toolchain resolution. "
            + "These toolchains will be considered before those declared in the WORKSPACE file by "
            + "register_toolchains()."
  )
  public List<Label> extraToolchains;

  @Option(
    name = "toolchain_resolution_override",
    converter = ToolchainResolutionOverrideConverter.class,
    allowMultiple = true,
    defaultValue = "",
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    effectTags = {
      OptionEffectTag.AFFECTS_OUTPUTS,
      OptionEffectTag.CHANGES_INPUTS,
      OptionEffectTag.LOADING_AND_ANALYSIS
    },
    help =
        "Override toolchain resolution for a toolchain type with a specific toolchain. "
            + "Example: --toolchain_resolution_override=@io_bazel_rules_go//:toolchain="
            + "@io_bazel_rules_go//:linux-arm64-toolchain"
  )
  public List<ToolchainResolutionOverride> toolchainResolutionOverrides;

  @Option(
    name = "toolchain_resolution_debug",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.LOGGING,
    effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
    help =
        "Print debug information while finding toolchains for a rule. This might help developers "
            + "of Bazel or Skylark rules with debugging failures due to missing toolchains."
  )
  public boolean toolchainResolutionDebug;

  @Option(
    name = "enabled_toolchain_types",
    defaultValue = "",
    converter = LabelListConverter.class,
    category = "semantics",
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
    help = "Signals that the given rule categories use platform-based toolchain resolution"
  )
  public List<Label> enabledToolchainTypes;

  @Override
  public PlatformOptions getHost() {
    PlatformOptions host = (PlatformOptions) getDefault();
    host.platforms =
        this.hostPlatform == null ? ImmutableList.of() : ImmutableList.of(this.hostPlatform);
    host.hostPlatform = this.hostPlatform;
    host.extraToolchains = this.extraToolchains;
    host.enabledToolchainTypes = this.enabledToolchainTypes;
    return host;
  }

  /** Data about which toolchain instance should be used for a given toolchain type. */
  @AutoValue
  public abstract static class ToolchainResolutionOverride {
    public static final ObjectCodec<ToolchainResolutionOverride> CODEC =
        new ToolchainResolutionOverrideCodec();

    public abstract Label toolchainType();

    public abstract Label toolchainLabel();

    private static ToolchainResolutionOverride create(Label toolchainType, Label toolchainLabel) {
      return new AutoValue_PlatformOptions_ToolchainResolutionOverride(
          toolchainType, toolchainLabel);
    }

    private static class ToolchainResolutionOverrideCodec
        implements ObjectCodec<ToolchainResolutionOverride> {
      @Override
      public Class<ToolchainResolutionOverride> getEncodedClass() {
        return ToolchainResolutionOverride.class;
      }

      @Override
      public void serialize(ToolchainResolutionOverride obj, CodedOutputStream codedOut)
          throws SerializationException, IOException {
        Label.CODEC.serialize(obj.toolchainType(), codedOut);
        Label.CODEC.serialize(obj.toolchainLabel(), codedOut);
      }

      @Override
      public ToolchainResolutionOverride deserialize(CodedInputStream codedIn)
          throws SerializationException, IOException {
        return ToolchainResolutionOverride.create(
            Label.CODEC.deserialize(codedIn), Label.CODEC.deserialize(codedIn));
      }
    }
  }

  /**
   * {@link Converter} implementation to create {@link ToolchainResolutionOverride} instances from
   * the value set in the flag.
   */
  public static class ToolchainResolutionOverrideConverter
      implements Converter<ToolchainResolutionOverride> {

    @Override
    public ToolchainResolutionOverride convert(String input) throws OptionsParsingException {
      int index = input.indexOf('=');
      if (index == -1) {
        throw new OptionsParsingException(
            "Toolchain resolution override not in the type=toolchain format");
      }
      Label toolchainType = convertOptionsLabel(input.substring(0, index));
      Label toolchain = convertOptionsLabel(input.substring(index + 1));

      return ToolchainResolutionOverride.create(toolchainType, toolchain);
    }

    @Override
    public String getTypeDescription() {
      return "a hard-coded override for toolchain resolution, "
          + "in the format toolchainTypeLabel=toolchainLabel";
    }
  }
}
