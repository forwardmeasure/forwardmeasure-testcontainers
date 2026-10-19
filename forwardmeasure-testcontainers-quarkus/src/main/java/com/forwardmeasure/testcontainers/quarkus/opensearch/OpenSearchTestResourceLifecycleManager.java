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
package com.forwardmeasure.testcontainers.quarkus.opensearch;

import com.forwardmeasure.testcontainers.opensearch.OpenSearchContainerConfiguration;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainer;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainers;
import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceConfigurableLifecycleManager;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testcontainers.utility.DockerImageName;

/**
 * Quarkus lifecycle adapter over the framework-neutral OpenSearch fixture.
 *
 * <p>With a Quarkus Dev Services network the container joins it under {@link
 * WithOpenSearchTestContainer#networkAlias()}; the network stays caller-owned. Credentials are
 * returned to Quarkus but never logged.
 */
public final class OpenSearchTestResourceLifecycleManager
    implements QuarkusTestResourceConfigurableLifecycleManager<WithOpenSearchTestContainer>,
        DevServicesContext.ContextAware {

  private WithOpenSearchTestContainer configuration;
  private Optional<String> containerNetworkId = Optional.empty();
  private OpenSearchTestContainer container;

  @Override
  public void init(WithOpenSearchTestContainer configuration) {
    this.configuration = configuration;
  }

  @Override
  public void setIntegrationTestContext(DevServicesContext context) {
    containerNetworkId = context.containerNetworkId();
  }

  @Override
  public Map<String, String> start() {
    if (configuration == null) {
      throw new IllegalStateException("OpenSearch Quarkus test resource was not configured");
    }
    if (container != null) {
      throw new IllegalStateException("OpenSearch Quarkus test resource is already started");
    }
    if (configuration.hostsProperty().isBlank()) {
      throw new IllegalStateException("hostsProperty must name a configuration property");
    }
    if (configuration.useNetworkEndpoint() && containerNetworkId.isEmpty()) {
      throw new IllegalStateException("A network endpoint requires a Quarkus Dev Services network");
    }

    OpenSearchContainerConfiguration providerConfiguration =
        new OpenSearchContainerConfiguration(
            DockerImageName.parse(configuration.image()),
            configuration.securityEnabled(),
            containerNetworkId,
            containerNetworkId.isPresent() ? List.of(configuration.networkAlias()) : List.of(),
            configuration.memoryBytes(),
            configuration.memorySwapBytes());
    try {
      container =
          configuration.shared()
              ? OpenSearchTestContainers.shared(providerConfiguration)
              : new OpenSearchTestContainer(providerConfiguration).start();
      URI endpoint =
          configuration.useNetworkEndpoint()
              ? container.networkEndpoint()
              : container.hostEndpoint();
      Map<String, String> properties = new LinkedHashMap<>();
      properties.put(configuration.hostsProperty(), endpoint.getHost() + ':' + endpoint.getPort());
      putIfNamed(properties, configuration.protocolProperty(), endpoint.getScheme());
      if (configuration.securityEnabled()) {
        putIfNamed(properties, configuration.usernameProperty(), container.username());
        putIfNamed(properties, configuration.passwordProperty(), container.password());
      }
      return Map.copyOf(properties);
    } catch (RuntimeException | Error failure) {
      stop();
      throw failure;
    }
  }

  /** Closes an owned container; a shared one keeps running for the rest of the JVM. */
  @Override
  public void stop() {
    OpenSearchTestContainer current = container;
    container = null;
    if (current != null && !current.isShared()) {
      current.close();
    }
  }

  private static void putIfNamed(Map<String, String> properties, String name, String value) {
    if (!name.isBlank()) {
      properties.put(name, value);
    }
  }
}
