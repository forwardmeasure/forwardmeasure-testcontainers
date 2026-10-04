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
package com.forwardmeasure.testcontainers.opensearch;

import java.net.URI;
import java.util.Objects;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

/** Explicitly owned OpenSearch Testcontainer with host and network HTTP endpoints. */
public final class OpenSearchTestContainer implements AutoCloseable {

  public static final int HTTP_PORT = 9200;

  private final OpenSearchContainerConfiguration configuration;
  private final OpenSearchContainer<?> container;
  private boolean started;
  private boolean closed;

  public OpenSearchTestContainer() {
    this(OpenSearchContainerConfiguration.defaults());
  }

  public OpenSearchTestContainer(OpenSearchContainerConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.container = configure(configuration);
  }

  public synchronized OpenSearchTestContainer start() {
    if (closed) {
      throw new IllegalStateException("OpenSearch test container has been closed");
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

  /** {@code http://host:mappedPort} - the container's real {@code getHttpHostAddress()}. */
  public URI hostEndpoint() {
    ensureStarted();
    return URI.create(container.getHttpHostAddress());
  }

  public URI networkEndpoint() {
    ensureStarted();
    String alias =
        configuration.networkAliases().stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No OpenSearch container network alias is configured"));
    return URI.create("http://" + alias + ':' + HTTP_PORT);
  }

  public boolean isSecurityEnabled() {
    return container.isSecurityEnabled();
  }

  public String username() {
    return container.getUsername();
  }

  public String password() {
    return container.getPassword();
  }

  public String containerName() {
    ensureStarted();
    return container.getContainerName();
  }

  public OpenSearchContainerConfiguration configuration() {
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

  private OpenSearchContainer<?> configure(OpenSearchContainerConfiguration settings) {
    DockerImageName image =
        settings.image().asCompatibleSubstituteFor("opensearchproject/opensearch");
    OpenSearchContainer<?> configured = new OpenSearchContainer<>(image);
    if (settings.securityEnabled()) {
      configured.withSecurityEnabled();
    }
    if (settings.memoryBytes() > 0 || settings.memorySwapBytes() > 0) {
      configured.withCreateContainerCmdModifier(
          command -> {
            if (settings.memoryBytes() > 0) {
              command.getHostConfig().withMemory(settings.memoryBytes());
            }
            if (settings.memorySwapBytes() > 0) {
              command.getHostConfig().withMemorySwap(settings.memorySwapBytes());
            }
          });
    }
    settings.networkId().ifPresent(networkId -> configured.withNetwork(existingNetwork(networkId)));
    if (!settings.networkAliases().isEmpty()) {
      configured.withNetworkAliases(settings.networkAliases().toArray(String[]::new));
    }
    return configured;
  }

  /**
   * Refers to a caller-owned Docker network without assuming ownership of its lifecycle - same
   * shape as {@code PostgreSqlTestContainer}/{@code MinioTestContainer}'s own identical helper.
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
      throw new IllegalStateException("OpenSearch test container is not running");
    }
  }
}
