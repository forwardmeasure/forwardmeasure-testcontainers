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

import java.net.InetSocketAddress;
import java.time.Duration;
import org.testcontainers.cassandra.CassandraContainer;

/** Explicitly owned single-node Cassandra fixture for real persistence contracts. */
public final class CassandraTestContainer implements AutoCloseable {
  private final CassandraContainer container;
  private boolean started;
  private boolean closed;

  public CassandraTestContainer() {
    container =
        new CassandraContainer("cassandra:5.0.5")
            .withEnv("MAX_HEAP_SIZE", "512M")
            .withEnv("HEAP_NEWSIZE", "100M")
            .withStartupTimeout(Duration.ofMinutes(3))
            .withCreateContainerCmdModifier(
                command -> command.getHostConfig().withMemory(2L * 1024 * 1024 * 1024));
  }

  /** Attaches Cassandra to the caller's disposable network for real service-to-database traffic. */
  public CassandraTestContainer(org.testcontainers.containers.Network network, String alias) {
    this();
    java.util.Objects.requireNonNull(network, "network");
    if (alias == null || alias.isBlank()) throw new IllegalArgumentException("alias is required");
    container.withNetwork(network).withNetworkAliases(alias);
  }

  public synchronized CassandraTestContainer start() {
    if (closed) throw new IllegalStateException("Cassandra test container has been closed");
    if (!started) {
      try {
        container.start();
        started = true;
      } catch (RuntimeException | Error failure) {
        close();
        throw failure;
      }
    }
    return this;
  }

  public InetSocketAddress contactPoint() {
    ensureStarted();
    return new InetSocketAddress(container.getHost(), container.getMappedPort(9042));
  }

  public String localDatacenter() {
    ensureStarted();
    return container.getLocalDatacenter();
  }

  private void ensureStarted() {
    if (!started || closed)
      throw new IllegalStateException("Cassandra test container is not running");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    container.close();
    started = false;
  }
}
