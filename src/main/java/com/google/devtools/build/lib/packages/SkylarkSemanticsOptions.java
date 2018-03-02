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

package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import java.io.Serializable;

/**
 * Contains options that affect Skylark's semantics.
 *
 * <p>These are injected into Skyframe (as an instance of {@link SkylarkSemantics}) when a new build
 * invocation occurs. Changing these options between builds will therefore trigger a reevaluation of
 * everything that depends on the Skylark interpreter &mdash; in particular, evaluation of all BUILD
 * and .bzl files.
 *
 * <p><em>To add a new option, update the following:</em>
 * <ul>
 *   <li>Add a new abstract method (which is interpreted by {@code AutoValue} as a field) to {@link
 *       SkylarkSemantics} and {@link SkylarkSemantics.Builder}. Set its default value in {@link
 *       SkylarkSemantics#DEFAULT_SEMANTICS}.
 *
 *   <li>Add a new {@code @Option}-annotated field to this class. The field name and default value
 *       should be the same as in {@link SkylarkSemantics}, and the option name in the annotation
 *       should be that name written in snake_case. Add a line to set the new field in {@link
 *       #toSkylarkSemantics}.
 *
 *   <li>Add a line to read and write the new field in {@link SkylarkSemanticsCodec#serialize} and
 *       {@link SkylarkSemanticsCodec#deserialize}.
 *
 *   <li>Add a line to set the new field in both {@link
 *       SkylarkSemanticsOptionsTest#buildRandomOptions} and {@link
 *       SkylarkSemanticsOptions#buildRandomSemantics}.
 *
 *   <li>Update manual documentation in site/docs/skylark/backward-compatibility.md. Also remember
 *       to update this when flipping a flag's default value.
 * </ul>
 * For both readability and correctness, the relative order of the options in all of these locations
 * must be kept consistent; to make it easy we use alphabetic order. The parts that need updating
 * are marked with the comment "<== Add new options here in alphabetic order ==>".
 */
public class SkylarkSemanticsOptions extends OptionsBase implements Serializable {

  // <== Add new options here in alphabetic order ==>

  @Option(
    name = "incompatible_bzl_disallow_load_after_statement",
    defaultValue = "false",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help =
        "If set to true, all `load` must be called at the top of .bzl files, before any other "
            + "statement."
  )
  public boolean incompatibleBzlDisallowLoadAfterStatement;

  @Option(
    name = "incompatible_checked_arithmetic",
    defaultValue = "true",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help = "If set to true, arithmetic operations throw an error in case of overflow/underflow."
  )
  public boolean incompatibleCheckedArithmetic;

  @Option(
    name = "incompatible_comprehension_variables_do_not_leak",
    defaultValue = "true",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help =
        "If set to true, loop variables in a comprehension shadow any existing variable by "
            + "the same name. If the existing variable was declared in the same scope that "
            + "contains the comprehension, then it also becomes inaccessible after the "
            + " comprehension executes."
  )
  public boolean incompatibleComprehensionVariablesDoNotLeak;

  @Option(
    name = "incompatible_depset_union",
    defaultValue = "false",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help =
        "If set to true, depset union using `+`, `|` or `.union` are forbidden. "
            + "Use the `depset` constructor instead."
  )
  public boolean incompatibleDepsetUnion;

  @Option(
    name = "incompatible_depset_is_not_iterable",
    defaultValue = "false",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help =
        "If set to true, depset type is not iterable. For loops and functions expecting an "
            + "iterable will reject depset objects. Use the `.to_list` method to explicitly "
            + "convert to a list."
  )
  public boolean incompatibleDepsetIsNotIterable;

  @Option(
    name = "incompatible_dict_literal_has_no_duplicates",
    defaultValue = "true",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help = "If set to true, the dictionary literal syntax doesn't allow duplicated keys."
  )
  public boolean incompatibleDictLiteralHasNoDuplicates;

  @Option(
    name = "incompatible_disable_glob_tracking",
    defaultValue = "false",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help = "If set to true, do not track the values of globs (this is used by rare specific cases"
  )
  public boolean incompatibleDisableGlobTracking;

  @Option(
    name = "incompatible_disallow_dict_plus",
    defaultValue = "false",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help = "If set to true, the `+` becomes disabled for dicts."
  )
  public boolean incompatibleDisallowDictPlus;

  @Option(
    name = "incompatible_disallow_keyword_only_args",
    defaultValue = "true",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help = "If set to true, disables the keyword-only argument syntax in function definition."
  )
  public boolean incompatibleDisallowKeywordOnlyArgs;

  @Option(
    name = "incompatible_disallow_toplevel_if_statement",
    defaultValue = "true",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help =
        "If set to true, 'if' statements are forbidden at the top-level "
            + "(outside a function definition)"
  )
  public boolean incompatibleDisallowToplevelIfStatement;

  @Option(
    name = "incompatible_disallow_uncalled_set_constructor",
    defaultValue = "true",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help = "If set to true, it's not allowed to use `set()` even if that code is never executed."
  )
  public boolean incompatibleDisallowUncalledSetConstructor;

  @Option(
    name = "incompatible_load_argument_is_label",
    defaultValue = "true",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help =
        "If set to true, the first argument of 'load' statements is a label (not a path). "
            + "It must start with '//' or ':'."
  )
  public boolean incompatibleLoadArgumentIsLabel;

  @Option(
      name = "incompatible_new_actions_api",
      defaultValue = "false",
      category = "incompatible changes",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If set to true, the API to create actions is only available on `ctx.actions`, "
          + "not on `ctx`."
  )
  public boolean incompatibleNewActionsApi;

  @Option(
    name = "incompatible_show_all_print_messages",
    defaultValue = "true",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help =
        "If set to true, the print function will generate DEBUG messages that aren't affected by "
            + "the --output_filter option. Otherwise it will generate filterable WARNING "
            + "messages."
  )
  public boolean incompatibleShowAllPrintMessages;

  @Option(
    name = "incompatible_string_is_not_iterable",
    defaultValue = "false",
    category = "incompatible changes",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
    help =
        "If set to true, iterating over a string will throw an error. String indexing and `len` "
            + "are still allowed."
  )
  public boolean incompatibleStringIsNotIterable;

  /** Used in an integration test to confirm that flags are visible to the interpreter. */
  @Option(
    name = "internal_skylark_flag_test_canary",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.UNKNOWN}
  )
  public boolean internalSkylarkFlagTestCanary;

  /** Constructs a {@link SkylarkSemantics} object corresponding to this set of option values. */
  public SkylarkSemantics toSkylarkSemantics() {
    return SkylarkSemantics.builder()
        // <== Add new options here in alphabetic order ==>
        .incompatibleBzlDisallowLoadAfterStatement(incompatibleBzlDisallowLoadAfterStatement)
        .incompatibleCheckedArithmetic(incompatibleCheckedArithmetic)
        .incompatibleComprehensionVariablesDoNotLeak(incompatibleComprehensionVariablesDoNotLeak)
        .incompatibleDepsetIsNotIterable(incompatibleDepsetIsNotIterable)
        .incompatibleDepsetUnion(incompatibleDepsetUnion)
        .incompatibleDictLiteralHasNoDuplicates(incompatibleDictLiteralHasNoDuplicates)
        .incompatibleDisableGlobTracking(incompatibleDisableGlobTracking)
        .incompatibleDisallowDictPlus(incompatibleDisallowDictPlus)
        .incompatibleDisallowKeywordOnlyArgs(incompatibleDisallowKeywordOnlyArgs)
        .incompatibleDisallowToplevelIfStatement(incompatibleDisallowToplevelIfStatement)
        .incompatibleDisallowUncalledSetConstructor(incompatibleDisallowUncalledSetConstructor)
        .incompatibleLoadArgumentIsLabel(incompatibleLoadArgumentIsLabel)
        .incompatibleNewActionsApi(incompatibleNewActionsApi)
        .incompatibleShowAllPrintMessages(incompatibleShowAllPrintMessages)
        .incompatibleStringIsNotIterable(incompatibleStringIsNotIterable)
        .internalSkylarkFlagTestCanary(internalSkylarkFlagTestCanary)
        .build();
  }
}
