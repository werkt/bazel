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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.BundlingRule;
import com.google.devtools.build.lib.util.FileType;

/**
 * Rule definition for {@code ios_test} rule in Bazel.
 */
public class IosTestRule implements RuleDefinition {

  @Override
  public RuleClass build(RuleClass.Builder builder, final RuleDefinitionEnvironment env) {
    final Label mcov = env.getToolsLabel("//tools/objc:mcov");
    return builder
        .requiresConfigurationFragments(
            ObjcConfiguration.class,
            J2ObjcConfiguration.class,
            AppleConfiguration.class,
            CppConfiguration.class)
        /*<!-- #BLAZE_RULE(ios_test).IMPLICIT_OUTPUTS -->
        <ul>
        <li><code><var>name</var>.ipa</code>: the test bundle as an
        <code>.ipa</code> file
        </ul>
        <!-- #END_BLAZE_RULE.IMPLICIT_OUTPUTS -->*/
        .setImplicitOutputsFunction(ReleaseBundlingSupport.IPA)
        /* <!-- #BLAZE_RULE(ios_test).ATTRIBUTE(target_device) -->
        The device against which to run the test.
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(
            attr(IosTest.TARGET_DEVICE, LABEL)
                .allowedFileTypes()
                .allowedRuleClasses("ios_device")
                .value(env.getToolsLabel("//tools/objc/sim_devices:default")))
        /* <!-- #BLAZE_RULE(ios_test).ATTRIBUTE(xctest) -->
        Deprecated. Does not affect how the test is built.
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr(IosTest.IS_XCTEST_ATTR, BOOLEAN).value(true))
        /* <!-- #BLAZE_RULE(ios_test).ATTRIBUTE(xctest_app) -->
        An <code>ios_application</code> target that contains the app bundle to test against with
        XCTest. This attribute is only valid if <code>xctest</code> is true.
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(
            attr(IosTest.XCTEST_APP_ATTR, LABEL)
                .value(env.getLabel("//tools/objc:xctest_app"))
                .allowedFileTypes()
                .mandatoryProviders(XcTestAppProvider.SKYLARK_CONSTRUCTOR.id()))
        .override(
            attr(BundlingRule.INFOPLIST_ATTR, LABEL)
                .value(env.getLabel("//tools/objc:xctest_infoplist"))
                .allowedFileTypes(ObjcRuleClasses.PLIST_TYPE))
        /* <!-- #BLAZE_RULE(ios_test).ATTRIBUTE(ios_test_target_device) -->
        The device against how to run the test. If this attribute is defined, the test will run on
        the lab device. Otherwise, the test will run on simulator.
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(
            attr(IosTest.TEST_TARGET_DEVICE_ATTR, LABEL)
                .allowedFileTypes()
                .allowedRuleClasses("ios_lab_device"))
        /* <!-- #BLAZE_RULE(ios_test).ATTRIBUTE(ios_device_arg) -->
        Extra arguments to pass to the <code>ios_test_target_device</code>'s binary. They should be
        in the form KEY=VALUE or simply KEY (check your device's documentation for allowed
        parameters).
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr(IosTest.DEVICE_ARG_ATTR, STRING_LIST))
        /* <!-- #BLAZE_RULE(ios_test).ATTRIBUTE(plugins) -->
        Plugins to pass to the test runner.
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr(IosTest.PLUGINS_ATTR, LABEL_LIST).allowedFileTypes(FileType.of("_deploy.jar")))
        .add(
            attr(IosTest.TEST_TEMPLATE_ATTR, LABEL)
                .value(env.getToolsLabel("//tools/objc:ios_test.sh.bazel_template")))
        .add(
            attr(IosTest.TEST_RUNNER_ATTR, LABEL)
                .value(env.getToolsLabel("//tools/objc:testrunner")))
        .add(
            attr(IosTest.MEMLEAKS_DEP_ATTR, LABEL)
                .value(env.getToolsLabel("//tools/objc/memleaks:memleaks")))
        .add(
            attr(IosTest.MEMLEAKS_PLUGIN_ATTR, LABEL)
                .value(env.getToolsLabel("//tools/objc:memleaks_plugin")))
        .add(
            attr(IosTest.OBJC_GCOV_ATTR, LABEL)
                .cfg(HostTransition.INSTANCE)
                .value(env.getToolsLabel("//tools/objc:gcov")))
        // TODO(b/65746853): provide a way to do this without passing the entire configuration
        .add(
            attr(IosTest.MCOV_TOOL_ATTR, LABEL)
                .cfg(HostTransition.INSTANCE)
                .value(
                    LateBoundDefault.fromTargetConfiguration(
                        BuildConfiguration.class,
                        mcov,
                        (rule, attributes, configuration) ->
                            configuration.isCodeCoverageEnabled() ? mcov : null)))
        .cfg(AppleCrosstoolTransition.APPLE_CROSSTOOL_TRANSITION)
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("ios_test")
        .type(RuleClassType.TEST)
        .ancestors(
            BaseRuleClasses.BaseRule.class,
            BaseRuleClasses.TestBaseRule.class,
            ObjcRuleClasses.ReleaseBundlingRule.class,
            ObjcRuleClasses.LinkingRule.class,
            ObjcRuleClasses.SimulatorRule.class)
        .factoryClass(IosTest.class)
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = ios_test, TYPE = TEST, FAMILY = Objective-C) -->

<p><strong>This rule is deprecated.</strong> Please use the new Apple build rules
(<a href="https://github.com/bazelbuild/rules_apple">https://github.com/bazelbuild/rules_apple</a>)
to build Apple targets.</p>

<p>This rule provides a way to build iOS unit tests written in the XCTest test framework
on both iOS simulator and real devices.
</p>

<!-- #END_BLAZE_RULE -->*/
