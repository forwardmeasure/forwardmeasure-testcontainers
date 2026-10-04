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
package com.forwardmeasure.testcontainers.quarkus.postgresql;

import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceConfigurableLifecycleManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testcontainers.utility.DockerImageName;

/** Quarkus lifecycle adapter over the framework-neutral PostgreSQL fixture. */
public final class PostgreSqlTestResourceLifecycleManager
    implements QuarkusTestResourceConfigurableLifecycleManager<WithPostgreSqlTestContainer>,
        DevServicesContext.ContextAware {

  private WithPostgreSqlTestContainer configuration;
  private Optional<String> containerNetworkId = Optional.empty();
  private PostgreSqlTestContainer container;

  @Override
  public void init(WithPostgreSqlTestContainer configuration) {
    this.configuration = configuration;
  }

  @Override
  public void setIntegrationTestContext(DevServicesContext context) {
    containerNetworkId = context.containerNetworkId();
  }

  @Override
  public Map<String, String> start() {
    if (configuration == null) {
      throw new IllegalStateException("PostgreSQL Quarkus test resource was not configured");
    }
    if (configuration.useNetworkJdbcUrl() && containerNetworkId.isEmpty()) {
      throw new IllegalStateException("A network JDBC URL requires a Quarkus Dev Services network");
    }

    PostgreSqlContainerConfiguration providerConfiguration =
        new PostgreSqlContainerConfiguration(
            DockerImageName.parse(configuration.image()),
            configuration.databaseName(),
            configuration.username(),
            configuration.password(),
            containerNetworkId,
            containerNetworkId.isPresent() ? List.of(configuration.networkAlias()) : List.of(),
            configuration.memoryBytes(),
            configuration.memorySwapBytes());
    container = new PostgreSqlTestContainer(providerConfiguration);
    try {
      container.start();
      return QuarkusPostgreSqlProperties.from(
          container, configuration.datasourceNames(), configuration.useNetworkJdbcUrl());
    } catch (RuntimeException | Error failure) {
      container.close();
      container = null;
      throw failure;
    }
  }

  @Override
  public void stop() {
    if (container != null) {
      container.close();
      container = null;
    }
  }
}
