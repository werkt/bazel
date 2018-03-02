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

package com.google.devtools.build.lib.sandbox;

import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.exec.AbstractSpawnStrategy;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/** Strategy that uses sandboxing to execute a process. */
// TODO(ulfjack): This class only exists for this annotation. Find a better way to handle this!
@ExecutionStrategy(
  name = {"sandboxed", "linux-sandbox"},
  contextType = SpawnActionContext.class
)
public final class LinuxSandboxedStrategy extends AbstractSpawnStrategy {
  LinuxSandboxedStrategy(SpawnRunner spawnRunner) {
    super(spawnRunner);
  }

  @Override
  public String toString() {
    return "sandboxed";
  }

  /**
   * Creates a sandboxed spawn runner that uses the {@code linux-sandbox} tool. If a spawn exceeds
   * its timeout, then it will be killed instantly.
   *
   * @param cmdEnv the command environment to use
   * @param sandboxBase path to the sandbox base directory
   * @param productName the product name to use
   */
  static LinuxSandboxedSpawnRunner create(
      CommandEnvironment cmdEnv, Path sandboxBase, String productName) throws IOException {
    return create(cmdEnv, sandboxBase, productName, Optional.empty());
  }

  /**
   * Creates a sandboxed spawn runner that uses the {@code linux-sandbox} tool. If a spawn exceeds
   * its timeout, then it will be killed after the specified delay.
   *
   * @param cmdEnv the command environment to use
   * @param sandboxBase path to the sandbox base directory
   * @param productName the product name to use
   * @param timeoutKillDelay an additional grace period before killing timing out commands
   */
  static LinuxSandboxedSpawnRunner create(
      CommandEnvironment cmdEnv, Path sandboxBase, String productName, Duration timeoutKillDelay)
      throws IOException {
    return create(cmdEnv, sandboxBase, productName, Optional.of(timeoutKillDelay));
  }

  /**
   * Creates a sandboxed spawn runner that uses the {@code linux-sandbox} tool.
   *
   * @param cmdEnv the command environment to use
   * @param sandboxBase path to the sandbox base directory
   * @param productName the product name to use
   * @param timeoutKillDelay an optional, additional grace period before killing timing out
   *     commands. If not present, then no grace period is used and commands are killed instantly.
   */
  static LinuxSandboxedSpawnRunner create(
      CommandEnvironment cmdEnv,
      Path sandboxBase,
      String productName,
      Optional<Duration> timeoutKillDelay)
      throws IOException {
    Path inaccessibleHelperFile = sandboxBase.getRelative("inaccessibleHelperFile");
    FileSystemUtils.touchFile(inaccessibleHelperFile);
    inaccessibleHelperFile.setReadable(false);
    inaccessibleHelperFile.setWritable(false);
    inaccessibleHelperFile.setExecutable(false);

    Path inaccessibleHelperDir = sandboxBase.getRelative("inaccessibleHelperDir");
    inaccessibleHelperDir.createDirectory();
    inaccessibleHelperDir.setReadable(false);
    inaccessibleHelperDir.setWritable(false);
    inaccessibleHelperDir.setExecutable(false);

    return new LinuxSandboxedSpawnRunner(
        cmdEnv,
        sandboxBase,
        productName,
        inaccessibleHelperFile,
        inaccessibleHelperDir,
        timeoutKillDelay);
  }
}
