/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.forwardmeasure.testcontainers.cassandra;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JVM-wide shared Cassandra fixtures: one started container per distinct {@link
 * CassandraContainerConfiguration} per JVM.
 *
 * <p>Starting Cassandra takes tens of seconds, so a large suite that starts one per test class
 * spends most of its time waiting. Sharing one container per test JVM and giving each test class a
 * fresh keyspace ({@link CassandraTestContainer#createUniqueKeyspace(String)}) keeps the isolation
 * that matters while paying the start-up cost once.
 *
 * <p>A shared fixture belongs to this registry, not to any test: its {@link
 * CassandraTestContainer#close()} is a no-op, so a test (or a try-with-resources block) cannot stop
 * it under other test classes. The registry stops every shared fixture from a JVM shutdown hook;
 * Testcontainers' Ryuk removes the containers if the JVM dies without running hooks.
 */
public final class CassandraTestContainers {

  private static final ConcurrentMap<CassandraContainerConfiguration, Slot> SHARED =
      new ConcurrentHashMap<>();

  static {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                CassandraTestContainers::releaseAll, "forwardmeasure-shared-cassandra-shutdown"));
  }

  private CassandraTestContainers() {}

  /**
   * The started shared fixture for {@code configuration}, starting it on first use. Concurrent
   * callers with equal configurations get the same instance and wait for one start; different
   * configurations start independently. A failed start is not cached: the failure propagates and
   * the next call tries again.
   */
  public static CassandraTestContainer shared(CassandraContainerConfiguration configuration) {
    Objects.requireNonNull(configuration, "configuration");
    return SHARED.computeIfAbsent(configuration, Slot::new).fixture();
  }

  private static void releaseAll() {
    SHARED.values().forEach(Slot::release);
  }

  /** Starts its fixture at most once, outside the map's own locks. */
  private static final class Slot {
    private final CassandraContainerConfiguration configuration;
    // Volatile so the shutdown hook can read it without waiting for a start in progress.
    private volatile CassandraTestContainer fixture;

    Slot(CassandraContainerConfiguration configuration) {
      this.configuration = configuration;
    }

    synchronized CassandraTestContainer fixture() {
      if (fixture == null) {
        CassandraTestContainer started = new CassandraTestContainer(configuration).start();
        started.markShared();
        fixture = started;
      }
      return fixture;
    }

    void release() {
      CassandraTestContainer current = fixture;
      if (current != null) {
        current.release();
      }
    }
  }
}
