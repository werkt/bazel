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

package com.google.devtools.build.lib.remote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

public class AtomicLogger {
  private AsynchronousFileChannel out;
  private AtomicInteger position;

  public AtomicLogger(String filename) throws IOException {
    position = new AtomicInteger();
    position.set(0);
    out = AsynchronousFileChannel.open(Paths.get(filename),
          StandardOpenOption.WRITE,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
  }

  public void log(String message) {
    byte[] data = message.getBytes();
    int curPosition = position.getAndAdd(data.length);
    out.write(ByteBuffer.wrap(data), curPosition);
  }

  public void close() throws IOException {
    out.close();
  }

  public boolean isOpen() {
    return out.isOpen();
  }
}
