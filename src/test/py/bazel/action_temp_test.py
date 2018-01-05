# Copyright 2017 The Bazel Authors. All rights reserved.
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

import os
import re
import unittest
from src.test.py.bazel import test_base


class ActionTempTest(test_base.TestBase):
  """Test that Bazel sets a TMP/TEMP/TMPDIR envvar for actions."""

  _invalidations = 0

  def testActionTemp(self):
    self._CreateWorkspace()
    strategies = self._SpawnStrategies()

    self.assertIn('standalone', strategies)
    if not test_base.TestBase.IsWindows():
      self.assertIn('sandboxed', strategies)
      self.assertIn('processwrapper-sandbox', strategies)

    bazel_bin = self._BazelOutputDirectory('bazel-bin')
    bazel_genfiles = self._BazelOutputDirectory('bazel-genfiles')

    self._AssertWritableTempDir(
        'standalone',
        expected_tmpdir_regex=(r'execroot[\\/].+[\\/]local-spawn-runner.[0-9]+'
                               r'[\\/]work$'),
        bazel_bin=bazel_bin,
        bazel_genfiles=bazel_genfiles)
    if not test_base.TestBase.IsWindows():
      expected_tmpdir_regex = r'bazel-sandbox/[0-9]+/execroot/.*/tmp$'
      self._AssertWritableTempDir('sandboxed', expected_tmpdir_regex, bazel_bin,
                                  bazel_genfiles)
      self._AssertWritableTempDir('processwrapper-sandbox',
                                  expected_tmpdir_regex, bazel_bin,
                                  bazel_genfiles)

  # Helper methods start here -------------------------------------------------

  def _AssertWritableTempDir(self,
                             strategy,
                             expected_tmpdir_regex,
                             bazel_bin,
                             bazel_genfiles,
                             env_add=None):
    self._invalidations += 1
    input_file_contents = str(self._invalidations)
    self._UpdateInputFile(input_file_contents)
    outputs = self._BuildRules(
        strategy,
        bazel_bin,
        bazel_genfiles,
        env_remove=self._TempEnvvars(),
        env_add=env_add)
    self.assertEqual(len(outputs), 2)
    self._AssertOutputFileContents(outputs['genrule'], input_file_contents,
                                   expected_tmpdir_regex)
    self._AssertOutputFileContents(outputs['skylark'], input_file_contents,
                                   expected_tmpdir_regex)

  def _UpdateInputFile(self, content):
    self.ScratchFile('foo/input.txt', [content])

  def _TempEnvvars(self):
    if test_base.TestBase.IsWindows():
      return ['TMP', 'TEMP']
    else:
      return ['TMPDIR']

  def _BazelOutputDirectory(self, info_key):
    exit_code, stdout, stderr = self.RunBazel(['info', info_key])
    self.AssertExitCode(exit_code, 0, stderr)
    return stdout[0]

  def _InvalidateActions(self, content):
    self.ScratchFile('foo/input.txt', [content])

  def _CreateWorkspace(self, build_flags=None):
    if test_base.TestBase.IsWindows():
      toolname = 'foo.cmd'
      toolsrc = [
          '@SETLOCAL ENABLEEXTENSIONS',
          '@echo ON',
          'if [%TMP%] == [] exit /B 1',
          'if [%TEMP%] == [] exit /B 1',
          'if not exist %2 exit /B 2',
          'set input_file=%2',
          '',
          'echo foo1 > %TMP%\\foo1.txt',
          'echo foo2 > %TEMP%\\foo2.txt',
          'type "%input_file:/=\\%" > %1',
          'type %TMP%\\foo1.txt >> %1',
          'type %TEMP%\\foo2.txt >> %1',
          'echo bar >> %1',
          'set TMP >> %1',
          'set TEMP >> %1',
          'exit /B 0',
      ]
    else:
      toolname = 'foo.sh'
      toolsrc = [
          '#!/bin/bash',
          'set -eu',
          'if [ -n "${TMPDIR:-}" ]; then',
          '  sleep 1',
          '  cat "$2" > "$1"',
          '  echo foo > "$TMPDIR/foo.txt"',
          '  cat "$TMPDIR/foo.txt" >> "$1"',
          '  echo bar >> "$1"',
          '  echo "TMPDIR=${TMPDIR}" >> "$1"',
          'else',
          '  exit 1',
          'fi',
      ]

    self.ScratchFile('WORKSPACE')
    self.ScratchFile('foo/' + toolname, toolsrc, executable=True)
    self.ScratchFile('foo/foo.bzl', [
        'def _impl(ctx):',
        '  ctx.actions.run(',
        '      executable=ctx.executable.tool,',
        '      arguments=[ctx.outputs.out.path, ctx.file.src.path],',
        '      inputs=[ctx.file.src],',
        '      outputs=[ctx.outputs.out])',
        '  return [DefaultInfo(files=depset([ctx.outputs.out]))]',
        '',
        'foorule = rule(',
        '    implementation=_impl,',
        '    attrs={"tool": attr.label(executable=True, cfg="host",',
        '                              allow_files=True, single_file=True),',
        '           "src": attr.label(allow_files=True, single_file=True)},',
        '    outputs={"out": "%{name}.txt"},',
        ')',
    ])

    self.ScratchFile('foo/BUILD', [
        'load("//foo:foo.bzl", "foorule")',
        '',
        'genrule(',
        '    name = "genrule",',
        '    tools = ["%s"],' % toolname,
        '    srcs = ["input.txt"],',
        '    outs = ["genrule.txt"],',
        '    cmd = "$(location %s) $@ $(location input.txt)",' % toolname,
        ')',
        '',
        'foorule(',
        '    name = "skylark",',
        '    src = "input.txt",',
        '    tool = "%s",' % toolname,
        ')',
    ])

  def _SpawnStrategies(self):
    """Returns the list of supported --spawn_strategy values."""
    # TODO(b/37617303): make test UI-independent
    exit_code, _, stderr = self.RunBazel([
        'build', '--color=no', '--curses=no', '--spawn_strategy=foo',
        '--noexperimental_ui'
    ])
    self.AssertExitCode(exit_code, 2, stderr)
    pattern = re.compile(
        r'^ERROR:.*is an invalid value for.*Valid values are: (.*)\.$')
    for line in stderr:
      m = pattern.match(line)
      if m:
        return set(e.strip() for e in m.groups()[0].split(','))
    return []

  def _BuildRules(self,
                  strategy,
                  bazel_bin,
                  bazel_genfiles,
                  env_remove=None,
                  env_add=None):

    def _ReadFile(path):
      with open(path, 'rt') as f:
        return [l.strip() for l in f]

    # TODO(b/37617303): make test UI-independent
    exit_code, _, stderr = self.RunBazel([
        'build',
        '--verbose_failures',
        '--noexperimental_ui',
        '--spawn_strategy=%s' % strategy,
        '//foo:genrule',
        '//foo:skylark',
    ], env_remove, env_add)
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertTrue(
        os.path.exists(os.path.join(bazel_genfiles, 'foo/genrule.txt')))
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'foo/skylark.txt')))

    return {
        'genrule': _ReadFile(os.path.join(bazel_genfiles, 'foo/genrule.txt')),
        'skylark': _ReadFile(os.path.join(bazel_bin, 'foo/skylark.txt'))
    }

  def _AssertOutputFileContents(self, lines, input_file_line,
                                expected_tmpdir_regex):
    if test_base.TestBase.IsWindows():
      # 6 lines = input_file_line, foo1, foo2, 'bar', TMP, TEMP
      self.assertEqual(len(lines), 6)
      self.assertEqual(lines[0:4], [input_file_line, 'foo1', 'foo2', 'bar'])
      tmp = [l for l in lines if l.startswith('TMP')]
      temp = [l for l in lines if l.startswith('TEMP')]
      self.assertEqual(len(tmp), 1)
      self.assertEqual(len(temp), 1)
      tmp = tmp[0].split('=', 1)[1]
      temp = temp[0].split('=', 1)[1]
      self.assertRegexpMatches(tmp, expected_tmpdir_regex)
      self.assertEqual(tmp, temp)
    else:
      # 4 lines = input_file_line, foo, bar, TMPDIR
      self.assertGreaterEqual(len(lines), 4)
      self.assertEqual(lines[0:3], [input_file_line, 'foo', 'bar'])
      tmpdir = lines[3].split('=', 1)[1]
      self.assertRegexpMatches(tmpdir, expected_tmpdir_regex)


if __name__ == '__main__':
  unittest.main()
