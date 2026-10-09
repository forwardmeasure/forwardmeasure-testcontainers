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
package com.forwardmeasure.testcontainers.gcs;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** Caller-owned GCS protocol emulator. This is not evidence of Google Cloud IAM behavior. */
public final class GcsEmulatorTestContainer implements AutoCloseable {
  public static final String DEFAULT_IMAGE = "fsouza/fake-gcs-server:1.54.0";
  private static final int PORT = 4443;
  private final GenericContainer<?> container;
  private final URI networkEndpoint;
  private boolean started;
  private boolean closed;

  public GcsEmulatorTestContainer(Network network, String alias) {
    Objects.requireNonNull(network, "network");
    if (alias == null || !alias.matches("[a-zA-Z0-9][a-zA-Z0-9-]*"))
      throw new IllegalArgumentException("A DNS alias is required");
    networkEndpoint = URI.create("http://" + alias + ":" + PORT);
    container =
        new GenericContainer<>(DEFAULT_IMAGE)
            .withNetwork(network)
            .withNetworkAliases(alias)
            .withExposedPorts(PORT)
            .withCommand(
                "-scheme",
                "http",
                "-port",
                Integer.toString(PORT),
                "-public-host",
                alias + ":" + PORT,
                "-external-url",
                networkEndpoint.toString())
            .withCreateContainerCmdModifier(
                command -> command.getHostConfig().withMemory(512L * 1024 * 1024))
            .waitingFor(Wait.forHttp("/storage/v1/b").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(1));
  }

  public synchronized GcsEmulatorTestContainer start() {
    if (closed) throw new IllegalStateException("GCS fixture is closed");
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

  public URI hostEndpoint() {
    ensureStarted();
    return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(PORT));
  }

  public URI networkEndpoint() {
    ensureStarted();
    return networkEndpoint;
  }

  /** Stops the real server process, preserving its container, for downstream outage assertions. */
  public void interruptService() {
    ensureStarted();
    container.getDockerClient().stopContainerCmd(container.getContainerId()).exec();
  }

  public void restoreService() {
    ensureStarted();
    container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
  }

  private void ensureStarted() {
    if (!started || closed) throw new IllegalStateException("GCS fixture is not running");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    container.close();
    started = false;
  }
}
