/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.forwardmeasure.testcontainers.valkey;

import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** Explicitly owned, authenticated Valkey for real fact-window and cache contracts. */
public final class ValkeyTestContainer implements AutoCloseable {
  public static final String DEFAULT_IMAGE = "valkey/valkey:8.1";
  private final GenericContainer<?> container;
  private final String password;
  private boolean started;
  private boolean closed;

  public ValkeyTestContainer(String password) {
    this.password = Objects.requireNonNull(password, "password");
    if (password.isBlank()) throw new IllegalArgumentException("password must not be blank");
    container =
        new GenericContainer<>(DEFAULT_IMAGE)
            .withExposedPorts(6379)
            .withCommand("--requirepass", password)
            .withCreateContainerCmdModifier(
                command -> command.getHostConfig().withMemory(256L * 1024 * 1024))
            .waitingFor(Wait.forListeningPort());
  }

  public ValkeyTestContainer(Network network, String alias, String password) {
    this(password);
    Objects.requireNonNull(network, "network");
    if (alias == null || alias.isBlank())
      throw new IllegalArgumentException("alias must not be blank");
    container.withNetwork(network).withNetworkAliases(alias);
  }

  public synchronized ValkeyTestContainer start() {
    if (closed) throw new IllegalStateException("Valkey test container has been closed");
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

  public String host() {
    ensureStarted();
    return container.getHost();
  }

  public int port() {
    ensureStarted();
    return container.getMappedPort(6379);
  }

  public String password() {
    return password;
  }

  private void ensureStarted() {
    if (!started || closed) throw new IllegalStateException("Valkey test container is not running");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    container.close();
    started = false;
  }
}
