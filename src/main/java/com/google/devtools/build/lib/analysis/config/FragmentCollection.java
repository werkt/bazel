// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableCollection;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.transitions.Transition;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.ClassObject;
import javax.annotation.Nullable;

/**
 * Represents a collection of configuration fragments in Skylark.
 */
// Documentation can be found at ctx.fragments
@Immutable
@SkylarkModule(name = "fragments",
    category = SkylarkModuleCategory.NONE,
    doc = "Possible fields are "
    + "<a href=\"apple.html\">apple</a>, <a href=\"cpp.html\">cpp</a>, "
    + "<a href=\"java.html\">java</a>, <a href=\"jvm.html\">jvm</a> and "
    + "<a href=\"objc.html\">objc</a>, <a href=\"android.html\">android</a>. "
    + "Access a specific fragment by its field name ex:</p><code>ctx.fragments.apple</code></p>"
    + "Note that rules have to declare their required fragments in order to access them "
    + "(see <a href=\"../rules.md#fragments\">here</a>).")
public class FragmentCollection implements ClassObject {
  private final RuleContext ruleContext;
  private final Transition transition;

  public FragmentCollection(RuleContext ruleContext, Transition transition) {
    this.ruleContext = ruleContext;
    this.transition = transition;
  }

  @Override
  @Nullable
  public Object getValue(String name) {
    return ruleContext.getSkylarkFragment(name, transition);
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return ruleContext.getSkylarkFragmentNames(transition);
  }

  @Override
  @Nullable
  public String getErrorMessageForUnknownField(String name) {
    return String.format(
        "There is no configuration fragment named '%s' in %s configuration. "
        + "Available fragments: %s",
        name, getConfigurationName(transition), fieldsToString());
  }

  private String fieldsToString() {
    return String.format("'%s'", Joiner.on("', '").join(getFieldNames()));
  }

  public static String getConfigurationName(Transition config) {
    return config.isHostTransition() ? "host" : "target";
  }

  @Override
  public String toString() {
    return getConfigurationName(transition) + ": [ " + fieldsToString() + "]";
  }
}
