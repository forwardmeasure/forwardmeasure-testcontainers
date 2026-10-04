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
package com.forwardmeasure.testcontainers.kafka;

import java.util.List;
import java.util.Objects;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Explicitly owned Kafka Testcontainer with host and network bootstrap-server endpoints. */
public final class KafkaTestContainer implements AutoCloseable {

  public static final int KAFKA_PORT = 9092;

  /**
   * The first port a caller-supplied network alias's own listener is bound to (each further alias,
   * if more than one, gets the next port up) - deliberately not {@link #KAFKA_PORT}: the real
   * {@code org.testcontainers.kafka.KafkaContainer} image already binds three default listeners of
   * its own at container ports 9092 ({@code PLAINTEXT}, host-reachable), 9093 ({@code BROKER},
   * inter-container) and 9094 ({@code CONTROLLER}, KRaft) - {@code withListener(String)} only ever
   * *adds* a further listener, it never replaces the default {@code PLAINTEXT} one, so reusing
   * {@link #KAFKA_PORT} for a custom alias listener collides with that default and the container
   * fails to start at all ({@code "Each listener must have a different port"} - confirmed live,
   * this port was the actual root cause before this fix; see {@code KafkaTestContainerTest}'s own
   * real network-alias test, which - before this fix - was the first test in this module to ever
   * actually start a container with both a host-reachable and a network-alias listener configured
   * together).
   */
  private static final int FIRST_NETWORK_LISTENER_PORT = 9095;

  /**
   * The container port a {@code host.docker.internal}-advertised listener binds to when {@link
   * KafkaContainerConfiguration#hostDockerInternalListenerEnabled()} is set - a distinct range from
   * {@link #FIRST_NETWORK_LISTENER_PORT} (which could occupy several ports, one per network alias)
   * so the two mechanisms never collide.
   */
  private static final int HOST_DOCKER_INTERNAL_LISTENER_PORT = 9100;

  private final KafkaContainerConfiguration configuration;
  private final KafkaContainer container;
  private boolean started;
  private boolean closed;

  public KafkaTestContainer() {
    this(KafkaContainerConfiguration.defaults());
  }

  public KafkaTestContainer(KafkaContainerConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.container = configure(configuration);
  }

  public synchronized KafkaTestContainer start() {
    if (closed) {
      throw new IllegalStateException("Kafka test container has been closed");
    }
    if (!started) {
      try {
        container.start();
        started = true;
      } catch (RuntimeException | Error failure) {
        container.close();
        closed = true;
        throw failure;
      }
    }
    return this;
  }

  /** {@code host:mappedPort} - the container's real {@code getBootstrapServers()}. */
  public String bootstrapServers() {
    ensureStarted();
    return container.getBootstrapServers();
  }

  /**
   * {@code alias:port} (the real port {@link #configure} bound this alias's own listener to - never
   * {@link #KAFKA_PORT}, see {@link #FIRST_NETWORK_LISTENER_PORT}'s own javadoc) - reachable only
   * by another container sharing this container's own configured network, via the real listener
   * {@link #configure} registers with {@code KafkaContainer#withListener(String)} (which itself
   * documents that doing so both adds the listener and registers its host as a real network alias -
   * no separate {@code withNetworkAliases} call is needed alongside it).
   */
  public String networkBootstrapServers() {
    ensureStarted();
    List<String> aliases = configuration.networkAliases();
    if (aliases.isEmpty()) {
      throw new IllegalStateException("No Kafka container network alias is configured");
    }
    return aliases.get(0) + ':' + (FIRST_NETWORK_LISTENER_PORT);
  }

  /**
   * {@code host.docker.internal:mappedPort} - the real listener {@link #configure} adds and
   * advertises under that hostname when {@link
   * KafkaContainerConfiguration#hostDockerInternalListenerEnabled()} is set. See that method's own
   * javadoc for why {@link #bootstrapServers()} alone doesn't work for a caller (e.g. a K3s pod via
   * {@code pod.spec.hostAliases}) that can resolve {@code host.docker.internal} but not
   * "localhost".
   */
  public String hostDockerInternalBootstrapServers() {
    ensureStarted();
    if (!configuration.hostDockerInternalListenerEnabled()) {
      throw new IllegalStateException(
          "hostDockerInternalListenerEnabled was not set - see"
              + " KafkaContainerConfiguration#withHostDockerInternalListener");
    }
    return "host.docker.internal:" + container.getMappedPort(HOST_DOCKER_INTERNAL_LISTENER_PORT);
  }

  public String containerName() {
    ensureStarted();
    return container.getContainerName();
  }

  public KafkaContainerConfiguration configuration() {
    return configuration;
  }

  public boolean isRunning() {
    return started && container.isRunning();
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      container.close();
      started = false;
      closed = true;
    }
  }

  private KafkaContainer configure(KafkaContainerConfiguration settings) {
    DockerImageName image = settings.image();
    KafkaContainer configured = new KafkaContainer(image);
    settings.networkId().ifPresent(networkId -> configured.withNetwork(existingNetwork(networkId)));
    int port = FIRST_NETWORK_LISTENER_PORT;
    for (String alias : settings.networkAliases()) {
      configured.withListener(alias + ':' + port);
      port++;
    }
    if (settings.hostDockerInternalListenerEnabled()) {
      configured.addExposedPort(HOST_DOCKER_INTERNAL_LISTENER_PORT);
      configured.withListener(
          "0.0.0.0:" + HOST_DOCKER_INTERNAL_LISTENER_PORT,
          () ->
              "host.docker.internal:"
                  + configured.getMappedPort(HOST_DOCKER_INTERNAL_LISTENER_PORT));
    }
    return configured;
  }

  /**
   * Refers to a caller-owned Docker network without assuming ownership of its lifecycle - same
   * shape as {@code OpenSearchTestContainer}/{@code PostgreSqlTestContainer}/{@code
   * MinioTestContainer}'s own identical helper.
   */
  private Network existingNetwork(String networkId) {
    return new Network() {
      @Override
      public String getId() {
        return networkId;
      }

      @Override
      public void close() {
        // The caller that supplied the network ID owns the network.
      }
    };
  }

  private void ensureStarted() {
    if (!isRunning()) {
      throw new IllegalStateException("Kafka test container is not running");
    }
  }
}
