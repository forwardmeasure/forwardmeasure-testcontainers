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
package com.forwardmeasure.testcontainers.quarkus.cassandra;

import com.forwardmeasure.testcontainers.cassandra.CassandraContainerConfiguration;
import com.forwardmeasure.testcontainers.cassandra.CassandraKeyspace;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainer;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainers;
import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceConfigurableLifecycleManager;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testcontainers.utility.DockerImageName;

/**
 * Quarkus lifecycle adapter over the framework-neutral Cassandra fixture.
 *
 * <p>With a Quarkus Dev Services network the container joins it under {@link
 * WithCassandraTestContainer#networkAlias()}; the network stays caller-owned. Credentials are
 * returned to Quarkus but never logged.
 */
public final class CassandraTestResourceLifecycleManager
    implements QuarkusTestResourceConfigurableLifecycleManager<WithCassandraTestContainer>,
        DevServicesContext.ContextAware {

  private WithCassandraTestContainer configuration;
  private Optional<String> containerNetworkId = Optional.empty();
  private CassandraTestContainer container;
  private CassandraKeyspace keyspace;

  @Override
  public void init(WithCassandraTestContainer configuration) {
    this.configuration = configuration;
  }

  @Override
  public void setIntegrationTestContext(DevServicesContext context) {
    containerNetworkId = context.containerNetworkId();
  }

  @Override
  public Map<String, String> start() {
    if (configuration == null) {
      throw new IllegalStateException("Cassandra Quarkus test resource was not configured");
    }
    if (container != null) {
      throw new IllegalStateException("Cassandra Quarkus test resource is already started");
    }
    if (configuration.contactPointsProperty().isBlank()) {
      throw new IllegalStateException("contactPointsProperty must name a configuration property");
    }
    if (configuration.useNetworkContactPoint() && containerNetworkId.isEmpty()) {
      throw new IllegalStateException(
          "A network contact point requires a Quarkus Dev Services network");
    }

    CassandraContainerConfiguration providerConfiguration =
        new CassandraContainerConfiguration(
            DockerImageName.parse(configuration.image()),
            configuration.maxHeapSize(),
            configuration.heapNewSize(),
            configuration.memoryBytes(),
            configuration.memorySwapBytes(),
            configuration.passwordAuthentication(),
            configuration.configurationFile().isBlank()
                ? Optional.empty()
                : Optional.of(configuration.configurationFile()),
            Duration.ofSeconds(configuration.startupTimeoutSeconds()),
            containerNetworkId,
            containerNetworkId.isPresent() ? List.of(configuration.networkAlias()) : List.of());
    try {
      container =
          configuration.shared()
              ? CassandraTestContainers.shared(providerConfiguration)
              : new CassandraTestContainer(providerConfiguration).start();
      Map<String, String> properties = new LinkedHashMap<>();
      InetSocketAddress contactPoint =
          configuration.useNetworkContactPoint()
              ? container.networkContactPoint()
              : container.contactPoint();
      properties.put(
          configuration.contactPointsProperty(),
          contactPoint.getHostString() + ':' + contactPoint.getPort());
      putIfNamed(properties, configuration.localDatacenterProperty(), container.localDatacenter());
      if (configuration.passwordAuthentication()) {
        putIfNamed(properties, configuration.usernameProperty(), container.superuserName());
        putIfNamed(properties, configuration.passwordProperty(), container.superuserPassword());
      }
      if (!configuration.keyspaceProperty().isBlank()) {
        keyspace = container.createUniqueKeyspace(configuration.keyspacePrefix());
        properties.put(configuration.keyspaceProperty(), keyspace.name());
      }
      return Map.copyOf(properties);
    } catch (RuntimeException | Error failure) {
      stop();
      throw failure;
    }
  }

  /** Drops the created keyspace and closes an owned container; a shared one keeps running. */
  @Override
  public void stop() {
    CassandraTestContainer current = container;
    CassandraKeyspace created = keyspace;
    container = null;
    keyspace = null;
    if (current == null) {
      return;
    }
    try {
      if (created != null && current.isRunning()) {
        current.dropKeyspace(created.name());
      }
    } finally {
      if (!current.isShared()) {
        current.close();
      }
    }
  }

  private static void putIfNamed(Map<String, String> properties, String name, String value) {
    if (!name.isBlank()) {
      properties.put(name, value);
    }
  }
}
