#!/bin/bash
#
# Copyright 2018 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Tests the proper checking of BUILD and BUILD.bazel files.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function create_target_directory {
  mkdir target
}

function create_empty_header {
  name=$1

  touch target/$name".h"
}

function write_header_inclusion {
  name=$1

  cat << EOF
#include "$name.h"
EOF
}

function write_returning_function {
  name=$1
  return_value=$2

  cat << EOF
int $name() {
  return $return_value;
}
EOF
}

function write_build_file {
  binary_name=$1
  impl_hdrs_or_srcs=$2

  impl_h_or_c="h"
  if [[ "${impl_hdrs_or_srcs:0:1}" != "h" ]]; then
    impl_h_or_c="c"
  fi

  cat << EOF
cc_library(
    name = "impl_dep",
    $impl_hdrs_or_srcs = ["impl_dep.$impl_h_or_c"],
)
EOF

  cat << EOF
cc_library(
    name = "impl_dep_includer",
    impl_deps = [":impl_dep"],
)
EOF

  cat << EOF
cc_binary(
    name = "$binary_name",
    srcs = ["$binary_name.c"],
    deps = [":impl_dep_includer"],
)
EOF
}

# Ensure headers from impl_deps dependencies are hidden
function test_header_exclusion {
  create_new_workspace
  create_target_directory

  dep_header_name="impl_dep"
  cc_binary_name="impl_hdr_includer"

  create_empty_header "$dep_header_name"
  write_header_inclusion "$dep_header_name" > target/$cc_binary_name.c
  write_returning_function "main" "0" >> target/$cc_binary_name.c
  write_build_file "$cc_binary_name" "hdrs" >> target/BUILD

  bazel build //target:$cc_binary_name >& $TEST_log && fail "build should fail"
  # XXX THIS WILL NOT FAIL IN THIS WAY UNDER SANDBOXING XXX
  expect_log "undeclared inclusion(s) in rule '//target:$cc_binary_name'"
}

# Ensure symbols from impl_deps dependencies are usable
function test_symbol_inclusion {
  create_new_workspace
  create_target_directory

  dep_source_name="impl_dep"
  impl_dep_function_name="impl_dep_func"
  cc_binary_name="impl_symbol_user"

  write_returning_function "$impl_dep_function_name" "0" > target/$dep_source_name.c
  write_returning_function "main" "$impl_dep_function_name()" > target/$cc_binary_name.c
  write_build_file "$cc_binary_name" "srcs" >> target/BUILD

  type bazel
  bazel build //target:$cc_binary_name >& $TEST_log || fail "build should succeed"
}

run_suite "implementation dependencies test"
