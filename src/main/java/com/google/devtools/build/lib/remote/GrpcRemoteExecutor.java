// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.remoteexecution.v1test.ExecuteRequest;
import com.google.devtools.remoteexecution.v1test.ExecuteResponse;
import com.google.devtools.remoteexecution.v1test.ExecutionGrpc;
import com.google.devtools.remoteexecution.v1test.ExecutionGrpc.ExecutionBlockingStub;
import com.google.longrunning.Operation;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Status;
import com.google.watcher.v1.Change;
import com.google.watcher.v1.ChangeBatch;
import com.google.watcher.v1.Request;
import com.google.watcher.v1.WatcherGrpc;
import com.google.watcher.v1.WatcherGrpc.WatcherBlockingStub;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** A remote work executor that uses gRPC for communicating the work, inputs and outputs. */
@ThreadSafe
class GrpcRemoteExecutor {

  private final Channel channel;
  private final CallCredentials callCredentials;
  private final int callTimeoutSecs;

  public GrpcRemoteExecutor(
      Channel channel,
      @Nullable CallCredentials callCredentials,
      int callTimeoutSecs) {
    Preconditions.checkArgument(callTimeoutSecs > 0, "callTimeoutSecs must be gt 0.");
    this.channel = channel;
    this.callCredentials = callCredentials;
    this.callTimeoutSecs = callTimeoutSecs;
  }

  private ExecutionBlockingStub execBlockingStub() {
    return ExecutionGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(callCredentials)
        .withDeadlineAfter(callTimeoutSecs, TimeUnit.SECONDS);
  }

  private WatcherBlockingStub watcherBlockingStub() {
    return WatcherGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(callCredentials);
  }

  private void handleStatus(Status statusProto, @Nullable ExecuteResponse resp) throws IOException {
    if (statusProto.getCode() == Code.OK.value()) {
      return;
    }
    throw new ExecutionStatusException(statusProto, resp);
  }

  private @Nullable ExecuteResponse getOperationResponse(Operation op) throws IOException {
    if (op.getResultCase() == Operation.ResultCase.ERROR) {
      handleStatus(op.getError(), null);
    }
    if (op.getDone()) {
      Preconditions.checkState(op.getResultCase() != Operation.ResultCase.RESULT_NOT_SET);
      try {
        ExecuteResponse resp = op.getResponse().unpack(ExecuteResponse.class);
        if (resp.hasStatus()) {
          handleStatus(resp.getStatus(), resp);
        }
        return resp;
      } catch (InvalidProtocolBufferException e) {
        throw new IOException(e);
      }
    }
    return null;
  }

  private ExecuteResponse handleChangeBatch(ChangeBatch cb, String opName) throws IOException {
    for (Change ch : cb.getChangesList()) {
      switch (ch.getState()) {
        case INITIAL_STATE_SKIPPED:
          continue;
        case ERROR:
          try {
            throw StatusProto.toStatusRuntimeException(
                ch.getData().unpack(Status.class));
          } catch (InvalidProtocolBufferException e) {
            throw new IOException(e);
          }
        case DOES_NOT_EXIST:
          // TODO(olaola): either make this retriable, or use a different exception.
          throw new IOException(
              String.format(
                  "Operation %s lost on the remote server.", opName));
        case EXISTS:
          Operation o = ch.getData().unpack(Operation.class);
          ExecuteResponse r = getOperationResponse(o);
          if (r != null) {
            return r;
          }
          continue;
        default:
          // This can only happen if the enum gets unexpectedly extended.
          throw new IOException(
              String.format("Illegal change state: %s", ch.getState()));
      }
    }
    throw new IOException(
        String.format("Watch request for %s terminated with no result.", opName));
  }

  /* Execute has two components: the execute call and the watch call.
   * This is the simple flow without any errors:
   *
   * - A call to execute returns an Operation object.
   * - That Operation may already have an inlined result; if so, we return that result.
   * - Otherwise, we call watch on that operation to receive a stream of Changes to the Operation
   *   object, until the first such change is an Operation with a result, which we return.
   *
   * Error possibilities:
   * - Any Operation object can have an error field instead of a result. Such Operations are
   *   completed and failed; however, some of these errors may be retriable. These errors should
   *   trigger a retry of the full execute+watch call, resulting in a new Operation.
   * - An execute call may fail with a retriable error (raise a StatusRuntimeException). We then
   *   retry that call.
   * - A watch call may fail with a retriable error (either raise a StatusRuntimeException, or
   *   return an ERROR in the ChangeBatch field). In that case, we retry the watch call only on the
   *   same operation object.
   * */
  public ExecuteResponse executeRemotely(ExecuteRequest request)
      throws IOException, InterruptedException {
    // Here all transient gRPC errors will be retried.
    Operation op = execBlockingStub().execute(request);
    ExecuteResponse resp = getOperationResponse(op);
    if (resp != null) {
      return resp;
    }
    Request wr = Request.newBuilder().setTarget(op.getName()).build();
    Iterator<ChangeBatch> replies = watcherBlockingStub().watch(wr);
    try {
      ExecuteResponse r = null;
      while (replies.hasNext() && r == null) {
        r = handleChangeBatch(replies.next(), op.getName());
      }
      return r;
    } finally {
      // The blocking streaming call closes correctly only when trailers and a Status
      // are received from the server so that onClose() is called on this call's
      // CallListener. Under normal circumstances (no cancel/errors), these are
      // guaranteed to be sent by the server only if replies.hasNext() has been called
      // after all replies from the stream have been consumed.
      try {
        while (replies.hasNext()) {
          replies.next();
        }
      } catch (StatusRuntimeException e) {
        // Cleanup: ignore exceptions, because the meaningful errors have already been
        // propagated.
      }
    }
  }
}
