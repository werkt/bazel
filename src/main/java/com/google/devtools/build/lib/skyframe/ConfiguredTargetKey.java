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

package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.ActionLookupValue.ActionLookupKey;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.skyframe.SkyFunctionName;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A (Label, Configuration) pair. Note that this pair may be used to look up the generating action
 * of an artifact. Callers may want to ensure that they have the correct configuration for this
 * purpose by passing in {@link BuildConfiguration#getArtifactOwnerTransition} in preference to
 * the raw configuration.
 */
public class ConfiguredTargetKey extends ActionLookupKey {
  private final Label label;
  @Nullable
  private final BuildConfiguration configuration;

  public ConfiguredTargetKey(Label label, @Nullable BuildConfiguration configuration) {
    this.label = Preconditions.checkNotNull(label);
    this.configuration = configuration;
  }

  @VisibleForTesting
  public ConfiguredTargetKey(ConfiguredTarget rule) {
    this(rule.getTarget().getLabel(), rule.getConfiguration());
  }

  public static ConfiguredTargetKey of(ConfiguredTarget configuredTarget) {
    AliasProvider aliasProvider = configuredTarget.getProvider(AliasProvider.class);
    Label label =
        aliasProvider != null ? aliasProvider.getAliasChain().get(0) : configuredTarget.getLabel();
    return of(label, configuredTarget.getConfiguration());
  }

  public static ConfiguredTargetKey of(Label label, @Nullable BuildConfiguration configuration) {
    return new ConfiguredTargetKey(label, configuration);
  }

  @Override
  public Label getLabel() {
    return label;
  }

  @Override
  protected SkyFunctionName getType() {
    return SkyFunctions.CONFIGURED_TARGET;
  }

  @Nullable
  public BuildConfiguration getConfiguration() {
    return configuration;
  }

  @Override
  public int hashCode() {
    int configVal = configuration == null ? 79 : configuration.hashCode();
    return 31 * label.hashCode() + configVal;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (!(obj instanceof ConfiguredTargetKey)) {
      return false;
    }
    ConfiguredTargetKey other = (ConfiguredTargetKey) obj;
    return Objects.equals(label, other.label) && Objects.equals(configuration, other.configuration);
  }

  public String prettyPrint() {
    if (label == null) {
      return "null";
    }
    return (configuration != null && configuration.isHostConfiguration())
        ? (label + " (host)") : label.toString();
  }

  @Override
  public String toString() {
    return String.format(
        "%s %s (%s %s)", label, (configuration == null ? "null" : configuration),
        System.identityHashCode(this),
        (configuration == null ? "null" : System.identityHashCode(configuration)));
  }
}
