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

import static com.google.common.base.Strings.isNullOrEmpty;

import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheBlockingStub;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheFutureStub;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteRetrier.ProgressiveBackoff;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.MissingDigestsFinder;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestOutputStream;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server. */
@ThreadSafe
public class GrpcCacheClient implements RemoteCacheClient, MissingDigestsFinder {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final CallCredentialsProvider callCredentialsProvider;
  private final ReferenceCountedChannel channel;
  private final RemoteOptions options;
  private final DigestUtil digestUtil;
  private final RemoteRetrier retrier;
  private final ByteStreamUploader uploader;
  private final int maxMissingBlobsDigestsPerMessage;

  private AtomicBoolean closed = new AtomicBoolean();

  @VisibleForTesting
  public GrpcCacheClient(
      ReferenceCountedChannel channel,
      CallCredentialsProvider callCredentialsProvider,
      RemoteOptions options,
      RemoteRetrier retrier,
      DigestUtil digestUtil,
      ByteStreamUploader uploader) {
    this.callCredentialsProvider = callCredentialsProvider;
    this.channel = channel;
    this.options = options;
    this.digestUtil = digestUtil;
    this.retrier = retrier;
    this.uploader = uploader;
    maxMissingBlobsDigestsPerMessage = computeMaxMissingBlobsDigestsPerMessage();
    Preconditions.checkState(
        maxMissingBlobsDigestsPerMessage > 0, "Error: gRPC message size too small.");
  }

  private int computeMaxMissingBlobsDigestsPerMessage() {
    final int overhead =
        FindMissingBlobsRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .build()
            .getSerializedSize();
    final int tagSize =
        FindMissingBlobsRequest.newBuilder()
                .addBlobDigests(Digest.getDefaultInstance())
                .build()
                .getSerializedSize()
            - FindMissingBlobsRequest.getDefaultInstance().getSerializedSize();
    // We assume all non-empty digests have the same size. This is true for fixed-length hashes.
    final int digestSize = digestUtil.compute(new byte[] {1}).getSerializedSize() + tagSize;
    return (options.maxOutboundMessageSize - overhead) / digestSize;
  }

  private ContentAddressableStorageFutureStub casFutureStub(RemoteActionExecutionContext context) {
    return ContentAddressableStorageGrpc.newFutureStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
            new NetworkTimeInterceptor(context::getNetworkTime))
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(options.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  private ByteStreamStub bsAsyncStub(RemoteActionExecutionContext context) {
    return ByteStreamGrpc.newStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
            new NetworkTimeInterceptor(context::getNetworkTime))
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(options.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  private ActionCacheBlockingStub acBlockingStub(RemoteActionExecutionContext context) {
    return ActionCacheGrpc.newBlockingStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
            new NetworkTimeInterceptor(context::getNetworkTime))
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(options.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  private ActionCacheFutureStub acFutureStub(RemoteActionExecutionContext context) {
    return ActionCacheGrpc.newFutureStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
            new NetworkTimeInterceptor(context::getNetworkTime))
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(options.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    uploader.release();
    channel.release();
  }

  /** Returns true if 'options.remoteCache' uses 'grpc' or an empty scheme */
  public static boolean isRemoteCacheOptions(RemoteOptions options) {
    if (isNullOrEmpty(options.remoteCache)) {
      return false;
    }
    // TODO(ishikhman): add proper URI validation/parsing for remote options
    return !(Ascii.toLowerCase(options.remoteCache).startsWith("http://")
        || Ascii.toLowerCase(options.remoteCache).startsWith("https://"));
  }

  @Override
  public ListenableFuture<ImmutableSet<Digest>> findMissingDigests(
      RemoteActionExecutionContext context, Iterable<Digest> digests) {
    if (Iterables.isEmpty(digests)) {
      return Futures.immediateFuture(ImmutableSet.of());
    }
    // Need to potentially split the digests into multiple requests.
    FindMissingBlobsRequest.Builder requestBuilder =
        FindMissingBlobsRequest.newBuilder().setInstanceName(options.remoteInstanceName);
    List<ListenableFuture<FindMissingBlobsResponse>> getMissingDigestCalls = new ArrayList<>();
    for (Digest digest : digests) {
      requestBuilder.addBlobDigests(digest);
      if (requestBuilder.getBlobDigestsCount() == maxMissingBlobsDigestsPerMessage) {
        getMissingDigestCalls.add(getMissingDigests(context, requestBuilder.build()));
        requestBuilder.clearBlobDigests();
      }
    }

    if (requestBuilder.getBlobDigestsCount() > 0) {
      getMissingDigestCalls.add(getMissingDigests(context, requestBuilder.build()));
    }

    ListenableFuture<ImmutableSet<Digest>> success =
        Futures.whenAllSucceed(getMissingDigestCalls)
            .call(
                () -> {
                  ImmutableSet.Builder<Digest> result = ImmutableSet.builder();
                  for (ListenableFuture<FindMissingBlobsResponse> callFuture :
                      getMissingDigestCalls) {
                    result.addAll(callFuture.get().getMissingBlobDigestsList());
                  }
                  return result.build();
                },
                MoreExecutors.directExecutor());

    RequestMetadata requestMetadata = context.getRequestMetadata();
    return Futures.catchingAsync(
        success,
        RuntimeException.class,
        (e) ->
            Futures.immediateFailedFuture(
                new IOException(
                    String.format(
                        "findMissingBlobs(%d) for %s: %s",
                        requestBuilder.getBlobDigestsCount(),
                        requestMetadata.getActionId(),
                        e.getMessage()),
                    e)),
        MoreExecutors.directExecutor());
  }

  private ListenableFuture<FindMissingBlobsResponse> getMissingDigests(
      RemoteActionExecutionContext context, FindMissingBlobsRequest request) {
    return Utils.refreshIfUnauthenticatedAsync(
        () -> retrier.executeAsync(() -> casFutureStub(context).findMissingBlobs(request)),
        callCredentialsProvider);
  }

  private ListenableFuture<ActionResult> handleStatus(ListenableFuture<ActionResult> download) {
    return Futures.catchingAsync(
        download,
        StatusRuntimeException.class,
        (sre) ->
            sre.getStatus().getCode() == Code.NOT_FOUND
                // Return null to indicate that it was a cache miss.
                ? Futures.immediateFuture(null)
                : Futures.immediateFailedFuture(new IOException(sre)),
        MoreExecutors.directExecutor());
  }

  @Override
  public ListenableFuture<ActionResult> downloadActionResult(
      RemoteActionExecutionContext context, ActionKey actionKey, boolean inlineOutErr) {
    GetActionResultRequest request =
        GetActionResultRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .setActionDigest(actionKey.getDigest())
            .setInlineStderr(inlineOutErr)
            .setInlineStdout(inlineOutErr)
            .build();
    return Utils.refreshIfUnauthenticatedAsync(
        () ->
            retrier.executeAsync(
                () -> handleStatus(acFutureStub(context).getActionResult(request))),
        callCredentialsProvider);
  }

  @Override
  public void uploadActionResult(
      RemoteActionExecutionContext context, ActionKey actionKey, ActionResult actionResult)
      throws IOException, InterruptedException {
    try {
      Utils.refreshIfUnauthenticated(
          () ->
              retrier.execute(
                  () ->
                      acBlockingStub(context)
                          .updateActionResult(
                              UpdateActionResultRequest.newBuilder()
                                  .setInstanceName(options.remoteInstanceName)
                                  .setActionDigest(actionKey.getDigest())
                                  .setActionResult(actionResult)
                                  .build())),
          callCredentialsProvider);
    } catch (StatusRuntimeException e) {
      throw new IOException(e);
    }
  }

  @Override
  public ListenableFuture<Void> downloadBlob(
      RemoteActionExecutionContext context, Digest digest, OutputStream out) {
    if (digest.getSizeBytes() == 0) {
      return Futures.immediateFuture(null);
    }

    @Nullable Supplier<Digest> digestSupplier = null;
    if (options.remoteVerifyDownloads) {
      DigestOutputStream digestOut = digestUtil.newDigestOutputStream(out);
      digestSupplier = digestOut::digest;
      out = digestOut;
    }

    return downloadBlob(context, digest, out, digestSupplier);
  }

  private ListenableFuture<Void> downloadBlob(
      RemoteActionExecutionContext context,
      Digest digest,
      OutputStream out,
      @Nullable Supplier<Digest> digestSupplier) {
    AtomicLong offset = new AtomicLong(0);
    ProgressiveBackoff progressiveBackoff = new ProgressiveBackoff(retrier::newBackoff);
    ListenableFuture<Void> downloadFuture =
        Utils.refreshIfUnauthenticatedAsync(
            () ->
                retrier.executeAsync(
                    () ->
                        requestRead(
                            context, offset, progressiveBackoff, digest, out, digestSupplier),
                    progressiveBackoff),
            callCredentialsProvider);

    return Futures.catchingAsync(
        downloadFuture,
        StatusRuntimeException.class,
        (e) -> Futures.immediateFailedFuture(new IOException(e)),
        MoreExecutors.directExecutor());
  }

  public static String getResourceName(String instanceName, Digest digest) {
    String resourceName = "";
    if (!instanceName.isEmpty()) {
      resourceName += instanceName + "/";
    }
    return resourceName + "blobs/" + DigestUtil.toString(digest);
  }

  private ListenableFuture<Void> requestRead(
      RemoteActionExecutionContext context,
      AtomicLong offset,
      ProgressiveBackoff progressiveBackoff,
      Digest digest,
      OutputStream out,
      @Nullable Supplier<Digest> digestSupplier) {
    String resourceName = getResourceName(options.remoteInstanceName, digest);
    SettableFuture<Void> future = SettableFuture.create();
    bsAsyncStub(context)
        .read(
            ReadRequest.newBuilder()
                .setResourceName(resourceName)
                .setReadOffset(offset.get())
                .build(),
            new StreamObserver<ReadResponse>() {
              @Override
              public synchronized void onNext(ReadResponse readResponse) {
                if (future.isDone()) {
                  logger.atWarning().log("Ignoring response after request completed");
                } else {
                  ByteString data = readResponse.getData();
                  try {
                    data.writeTo(out);
                    offset.addAndGet(data.size());
                  } catch (IOException e) {
                    // Cancel the call.
                    throw new RuntimeException(e);
                  }
                  // reset the stall backoff because we've made progress or been kept alive
                  progressiveBackoff.reset();
                }
              }

              @Override
              public synchronized void onError(Throwable t) {
                Status status = Status.fromThrowable(t);
                if (status.getCode() == Status.Code.NOT_FOUND) {
                  future.setException(new CacheNotFoundException(digest));
                } else {
                  future.setException(t);
                }
              }

              @Override
              public synchronized void onCompleted() {
                try {
                  if (digestSupplier != null) {
                    Utils.verifyBlobContents(digest, digestSupplier.get());
                  }
                  out.flush();
                  future.set(null);
                } catch (IOException e) {
                  future.setException(e);
                } catch (RuntimeException e) {
                  logger.atWarning().withCause(e).log("Unexpected exception");
                  future.setException(e);
                }
              }
            });
    return future;
  }

  @Override
  public ListenableFuture<Void> uploadFile(
      RemoteActionExecutionContext context, Digest digest, Path path) {
    return uploader.uploadBlobAsync(
        context,
        digest,
        Chunker.builder().setInput(digest.getSizeBytes(), path).build(),
        /* forceUpload= */ true);
  }

  @Override
  public ListenableFuture<Void> uploadBlob(
      RemoteActionExecutionContext context, Digest digest, ByteString data) {
    return uploader.uploadBlobAsync(
        context,
        digest,
        Chunker.builder().setInput(data.toByteArray()).build(),
        /* forceUpload= */ true);
  }
}
