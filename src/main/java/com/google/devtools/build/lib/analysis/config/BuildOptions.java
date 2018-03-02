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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.common.options.InvocationPolicyEnforcer;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClassProvider;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nullable;

/**
 * Stores the command-line options from a set of configuration fragments.
 */
public final class BuildOptions implements Cloneable, Serializable {
  public static final ObjectCodec<BuildOptions> CODEC = new BuildOptionsCodec();

  private static final Comparator<Class<? extends FragmentOptions>>
      lexicalFragmentOptionsComparator = Comparator.comparing(Class::getName);

  /**
   * Creates a BuildOptions object with all options set to their default values, processed by the
   * given {@code invocationPolicy}.
   */
  static BuildOptions createDefaults(
      Iterable<Class<? extends FragmentOptions>> options, InvocationPolicy invocationPolicy) {
    return of(options, createDefaultParser(options, invocationPolicy));
  }

  private static OptionsParser createDefaultParser(
      Iterable<Class<? extends FragmentOptions>> options, InvocationPolicy invocationPolicy) {
    OptionsParser optionsParser = OptionsParser.newOptionsParser(options);
    try {
      new InvocationPolicyEnforcer(invocationPolicy).enforce(optionsParser);
    } catch (OptionsParsingException e) {
      throw new IllegalStateException(e);
    }
    return optionsParser;
  }

  /**
   * Creates a new BuildOptions instance for host.
   */
  public BuildOptions createHostOptions() {
    Builder builder = builder();
    for (FragmentOptions options : fragmentOptionsMap.values()) {
      builder.add(options.getHost());
    }
    return builder.build();
  }

  /**
   * Returns an equivalent instance to this one with only options from the given
   * {@link FragmentOptions} classes.
   */
  public BuildOptions trim(Set<Class<? extends FragmentOptions>> optionsClasses) {
    Builder builder = builder();
    for (FragmentOptions options : fragmentOptionsMap.values()) {
      if (optionsClasses.contains(options.getClass())
          // TODO(bazel-team): make this non-hacky while not requiring BuildConfiguration access
          // to BuildOptions.
          || options.toString().contains("BuildConfiguration$Options")) {
        builder.add(options);
      }
    }
    return builder.build();
  }

  /**
   * Creates a BuildOptions class by taking the option values from an options provider
   * (eg. an OptionsParser).
   */
  public static BuildOptions of(
      Iterable<Class<? extends FragmentOptions>> optionsList, OptionsClassProvider provider) {
    Builder builder = builder();
    for (Class<? extends FragmentOptions> optionsClass : optionsList) {
      builder.add(provider.getOptions(optionsClass));
    }
    return builder.build();
  }

  /**
   * Creates a BuildOptions class by taking the option values from command-line arguments.
   */
  @VisibleForTesting
  public static BuildOptions of(List<Class<?extends FragmentOptions>> optionsList, String... args)
      throws OptionsParsingException {
    Builder builder = builder();
    OptionsParser parser = OptionsParser.newOptionsParser(
        ImmutableList.<Class<? extends OptionsBase>>copyOf(optionsList));
    parser.parse(args);
    for (Class<? extends FragmentOptions> optionsClass : optionsList) {
      builder.add(parser.getOptions(optionsClass));
    }
    return builder.build();
  }

  /**
   * Returns the actual instance of a FragmentOptions class.
   */
  public <T extends FragmentOptions> T get(Class<T> optionsClass) {
    FragmentOptions options = fragmentOptionsMap.get(optionsClass);
    Preconditions.checkNotNull(options, "fragment options unavailable: " + optionsClass.getName());
    return optionsClass.cast(options);
  }

  /**
   * Returns true if these options contain the given {@link FragmentOptions}.
   */
  public boolean contains(Class<? extends FragmentOptions> optionsClass) {
    return fragmentOptionsMap.containsKey(optionsClass);
  }

  // It would be very convenient to use a Multimap here, but we cannot do that because we need to
  // support defaults labels that have zero elements.
  ImmutableMap<String, ImmutableSet<Label>> getDefaultsLabels() {
    Map<String, Set<Label>> collector  = new TreeMap<>();
    for (FragmentOptions fragment : fragmentOptionsMap.values()) {
      for (Map.Entry<String, Set<Label>> entry : fragment.getDefaultsLabels().entrySet()) {
        if (!collector.containsKey(entry.getKey())) {
          collector.put(entry.getKey(), new TreeSet<Label>());
        }
        collector.get(entry.getKey()).addAll(entry.getValue());
      }
    }

    ImmutableMap.Builder<String, ImmutableSet<Label>> result = new ImmutableMap.Builder<>();
    for (Map.Entry<String, Set<Label>> entry : collector.entrySet()) {
      result.put(entry.getKey(), ImmutableSet.copyOf(entry.getValue()));
    }

    return result.build();
  }

  ImmutableList<String> getDefaultsRules() {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    for (FragmentOptions fragment : fragmentOptionsMap.values()) {
      result.addAll(fragment.getDefaultsRules());
    }

    return result.build();
  }

  /**
   * Returns true if actions should be enabled for this configuration.
   */
  public boolean enableActions() {
    // It's not necessarily safe to cache this value. This is because BuildOptions is not immutable.
    // So caching the value correctly would require keeping it updated after relevant changes.
    for (FragmentOptions fragment : fragmentOptionsMap.values()) {
      if (!fragment.enableActions()) {
        return false;
      }
    }
    return true;
   }

  /**
   * The cache key for the options collection. Recomputes cache key every time it's called.
   */
  public String computeCacheKey() {
    StringBuilder keyBuilder = new StringBuilder();
    for (FragmentOptions options : fragmentOptionsMap.values()) {
      keyBuilder.append(options.cacheKey());
    }
    return keyBuilder.toString();
  }

  /**
   * String representation of build options.
   */
  @Override
  public String toString() {
    StringBuilder stringBuilder = new StringBuilder();
    for (FragmentOptions options : fragmentOptionsMap.values()) {
      stringBuilder.append(options);
    }
    return stringBuilder.toString();
  }

  /**
   * Returns the options contained in this collection.
   */
  public Collection<FragmentOptions> getOptions() {
    return fragmentOptionsMap.values();
  }

  /**
   * Creates a copy of the BuildOptions object that contains copies of the FragmentOptions.
   */
  @Override
  public BuildOptions clone() {
    ImmutableMap.Builder<Class<? extends FragmentOptions>, FragmentOptions> builder =
        ImmutableMap.builder();
    for (Map.Entry<Class<? extends FragmentOptions>, FragmentOptions> entry :
        fragmentOptionsMap.entrySet()) {
      builder.put(entry.getKey(), entry.getValue().clone());
    }
    return new BuildOptions(builder.build());
  }

  /**
   * Lazily initialize {@link #fingerprint} and {@link #hashCode}. Keeps computation off critical
   * path of build, while still avoiding expensive computation for equality and hash code each time.
   *
   * <p>We check for nullity of {@link #fingerprint} to see if this method has already been called.
   * Using {@link #hashCode} after this method is called is safe because it is set here before
   * {@link #fingerprint} is set, so if {@link #fingerprint} is non-null then {@link #hashCode} is
   * definitely set.
   */
  private void maybeInitializeFingerprintAndHashCode() {
    if (fingerprint != null) {
      return;
    }
    synchronized (this) {
      if (fingerprint != null) {
        return;
      }
      Fingerprint fingerprint = new Fingerprint();
      for (Map.Entry<Class<? extends FragmentOptions>, FragmentOptions> entry :
          fragmentOptionsMap.entrySet()) {
        fingerprint.addString(entry.getKey().getName());
        fingerprint.addString(entry.getValue().cacheKey());
      }
      byte[] computedFingerprint = fingerprint.digestAndReset();
      hashCode = Arrays.hashCode(computedFingerprint);
      this.fingerprint = computedFingerprint;
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    } else if (!(other instanceof BuildOptions)) {
      return false;
    } else {
      maybeInitializeFingerprintAndHashCode();
      BuildOptions otherOptions = (BuildOptions) other;
      otherOptions.maybeInitializeFingerprintAndHashCode();
      return Arrays.equals(this.fingerprint, otherOptions.fingerprint);
    }
  }

  @Override
  public int hashCode() {
    maybeInitializeFingerprintAndHashCode();
    return hashCode;
  }

  // Lazily initialized.
  @Nullable private volatile byte[] fingerprint;
  private volatile int hashCode;

  /**
   * Maps options class definitions to FragmentOptions objects.
   */
  private final ImmutableMap<Class<? extends FragmentOptions>, FragmentOptions> fragmentOptionsMap;

  private BuildOptions(
      ImmutableMap<Class<? extends FragmentOptions>, FragmentOptions> fragmentOptionsMap) {
    this.fragmentOptionsMap = fragmentOptionsMap;
  }

  /**
   * Creates a builder object for BuildOptions
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder class for BuildOptions.
   */
  public static class Builder {
    /**
     * Adds a new FragmentOptions instance to the builder. Overrides previous instances of the
     * exact same subclass of FragmentOptions.
     */
    public <T extends FragmentOptions> Builder add(T options) {
      builderMap.put(options.getClass(), options);
      return this;
    }

    public BuildOptions build() {
      return new BuildOptions(
          ImmutableSortedMap.copyOf(builderMap, lexicalFragmentOptionsComparator));
    }

    private Map<Class<? extends FragmentOptions>, FragmentOptions> builderMap;

    private Builder() {
      builderMap = new HashMap<>();
    }
  }

  private static class BuildOptionsCodec implements ObjectCodec<BuildOptions> {
    @Override
    public Class<BuildOptions> getEncodedClass() {
      return BuildOptions.class;
    }

    @Override
    public void serialize(BuildOptions buildOptions, CodedOutputStream codedOut)
        throws IOException, SerializationException {
      Collection<FragmentOptions> fragmentOptions = buildOptions.getOptions();
      codedOut.writeInt32NoTag(fragmentOptions.size());
      for (FragmentOptions options : buildOptions.getOptions()) {
        FragmentOptions.CODEC.serialize(options, codedOut);
      }
    }

    @Override
    public BuildOptions deserialize(CodedInputStream codedIn)
        throws IOException, SerializationException {
      BuildOptions.Builder builder = BuildOptions.builder();
      int length = codedIn.readInt32();
      for (int i = 0; i < length; ++i) {
        builder.add(FragmentOptions.CODEC.deserialize(codedIn));
      }
      return builder.build();
    }
  }
}
