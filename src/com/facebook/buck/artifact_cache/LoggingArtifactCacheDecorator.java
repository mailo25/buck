/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.artifact_cache;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.rules.RuleKey;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Decorator for wrapping a {@link ArtifactCache} to log a {@link ArtifactCacheEvent} for the start
 * and finish of each event. The underlying cache must only provide synchronous operations.
 */
public class LoggingArtifactCacheDecorator implements ArtifactCache, CacheDecorator {
  private final BuckEventBus eventBus;
  private final ArtifactCache delegate;
  private final ArtifactCacheEventFactory eventFactory;

  public LoggingArtifactCacheDecorator(
      BuckEventBus eventBus, ArtifactCache delegate, ArtifactCacheEventFactory eventFactory) {
    this.eventBus = eventBus;
    this.delegate = delegate;
    this.eventFactory = eventFactory;
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    ArtifactCacheEvent.Started started =
        eventFactory.newFetchStartedEvent(ImmutableSet.of(ruleKey));
    eventBus.post(started);
    CacheResult fetchResult = delegate.fetch(ruleKey, output);
    eventBus.post(eventFactory.newFetchFinishedEvent(started, fetchResult));
    return fetchResult;
  }

  @Override
  public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
    ArtifactCacheEvent.Started started =
        eventFactory.newStoreStartedEvent(info.getRuleKeys(), info.getMetadata());
    eventBus.post(started);
    ListenableFuture<Void> storeFuture = delegate.store(info, output);
    eventBus.post(eventFactory.newStoreFinishedEvent(started));
    return storeFuture;
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return delegate.getCacheReadMode();
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public ArtifactCache getDelegate() {
    return delegate;
  }
}
