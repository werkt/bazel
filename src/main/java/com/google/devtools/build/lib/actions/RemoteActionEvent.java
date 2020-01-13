// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.devtools.build.lib.events.ExtendedEventHandler.ProgressLike;

/**
 * Notifies that an in-flight action is doing something remotely.
 *
 * <p>This event should only appear in-between corresponding {@link RunningStartedEvent} and {@link
 * ActionCompletionEvent} events.
 */
public class RemoteActionEvent implements ProgressLike {

  public enum State {
    UNKNOWN,
    COMPOSITING,
    CACHE_CHECKING,
    FINDING_MISSING_INPUTS,
    UPLOADING_INPUTS,
    EXECUTE_REQUESTED,
    EXECUTE_QUEUED,
    EXECUTE_CACHE_CHECKING,
    EXECUTING,
    EXECUTE_COMPLETED,
    DOWNLOADING_RESULTS,
  }

  private final ActionExecutionMetadata action;
  private final String remoteActionId;
  private final State state;
  private final long uploaded;
  private final long uploadTotal;
  private final int uploadCount;
  private final long downloaded;
  private final long downloadTotal;
  private final int downloadCount;
  private final String operationName;

  /** Constructs a new event. */
  public RemoteActionEvent(
      ActionExecutionMetadata action,
      State state,
      String remoteActionId,
      long uploaded,
      long uploadTotal,
      int uploadCount,
      long downloaded,
      long downloadTotal,
      int downloadCount,
      String operationName) {
    this.action = action;
    this.remoteActionId = remoteActionId;
    this.state = state;
    this.uploaded = uploaded;
    this.uploadTotal = uploadTotal;
    this.uploadCount = uploadCount;
    this.downloaded = downloaded;
    this.downloadTotal = downloadTotal;
    this.downloadCount = downloadCount;
    this.operationName = operationName;
  }

  /** Gets the metadata associated with the action that is running. */
  public ActionExecutionMetadata getActionMetadata() {
    return action;
  }

  public State getState() {
    return state;
  }

  public String getRemoteActionId() {
    return remoteActionId;
  }

  public long getUploaded() {
    return uploaded;
  }

  public long getUploadTotal() {
    return uploadTotal;
  }

  public int getUploadCount() {
    return uploadCount;
  }

  public long getDownloaded() {
    return downloaded;
  }

  public long getDownloadTotal() {
    return downloadTotal;
  }

  public int getDownloadCount() {
    return downloadCount;
  }

  public String getOperationName() {
    return operationName;
  }
}
