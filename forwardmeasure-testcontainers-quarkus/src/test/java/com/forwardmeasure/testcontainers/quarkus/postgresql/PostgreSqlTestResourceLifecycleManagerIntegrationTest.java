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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WithPostgreSqlTestContainer(
    databaseName = "quarkus_contract",
    datasourceNames = {"audit"})
class PostgreSqlTestResourceLifecycleManagerIntegrationTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(PostgreSqlTestResourceLifecycleManagerIntegrationTest.class);

  @Test
  void startsRealPostgresAndProducesDefaultAndNamedDatasourceProperties() throws Exception {
    WithPostgreSqlTestContainer configuration =
        getClass().getAnnotation(WithPostgreSqlTestContainer.class);
    PostgreSqlTestResourceLifecycleManager manager = new PostgreSqlTestResourceLifecycleManager();
    manager.init(configuration);

    Map<String, String> properties = manager.start();
    LOGGER.info(
        "Started, produced {} properties (passwords redacted): {}",
        properties.size(),
        properties.entrySet().stream()
            .map(
                entry ->
                    entry.getKey().toLowerCase(java.util.Locale.ROOT).contains("password")
                        ? entry.getKey() + "=<redacted>"
                        : entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(", ")));
    try {
      assertEquals(
          properties.get("quarkus.datasource.jdbc.url"),
          properties.get("quarkus.datasource.\"audit\".jdbc.url"));
      assertEquals("forwardmeasure", properties.get("quarkus.datasource.\"audit\".username"));

      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setUrl(properties.get("quarkus.datasource.jdbc.url"));
      dataSource.setUser(properties.get("quarkus.datasource.username"));
      dataSource.setPassword(properties.get("quarkus.datasource.password"));
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var result = statement.executeQuery("select current_database()")) {
        assertTrue(result.next());
        LOGGER.info("current_database={}", result.getString(1));
        assertEquals("quarkus_contract", result.getString(1));
      }
    } finally {
      LOGGER.info("Stopping lifecycle manager");
      manager.stop();
    }
  }

  @WithPostgreSqlTestContainer(
      databaseName = "network_contract",
      useNetworkJdbcUrl = true,
      networkAlias = "adapter-postgres",
      datasourceNames = {"audit", "", " "})
  static class NetworkConfiguration {}

  @Test
  void incompleteLifecycleConfigurationFailsBeforeStartingAContainer() {
    var manager = new PostgreSqlTestResourceLifecycleManager();
    manager.stop();
    assertThrows(IllegalStateException.class, manager::start);
    manager.init(NetworkConfiguration.class.getAnnotation(WithPostgreSqlTestContainer.class));
    assertThrows(IllegalStateException.class, manager::start);
    manager.stop();
  }

  @Test
  void devServicesNetworkProducesUsableUrlsAndRemainsCallerOwned() throws Exception {
    try (var network = org.testcontainers.containers.Network.newNetwork()) {
      var manager = new PostgreSqlTestResourceLifecycleManager();
      manager.init(NetworkConfiguration.class.getAnnotation(WithPostgreSqlTestContainer.class));
      manager.setIntegrationTestContext(
          new io.quarkus.test.common.DevServicesContext() {
            public Map<String, String> devServicesProperties() {
              return Map.of();
            }

            public java.util.Optional<String> containerNetworkId() {
              return java.util.Optional.of(network.getId());
            }
          });
      try {
        var properties = manager.start();
        assertEquals(
            "jdbc:postgresql://adapter-postgres:5432/network_contract",
            properties.get("quarkus.datasource.jdbc.url"));
        assertEquals(
            properties.get("quarkus.datasource.jdbc.url"),
            properties.get("quarkus.datasource.\"audit\".jdbc.url"));
        assertFalse(
            properties.keySet().stream()
                .anyMatch(key -> key.contains("\"\"") || key.contains("\" \"")));
        assertThrows(UnsupportedOperationException.class, () -> properties.put("changed", "value"));
        try (var client =
            new org.testcontainers.containers.GenericContainer<>("postgres:18-alpine")
                .withNetwork(network)
                .withEnv("PGPASSWORD", properties.get("quarkus.datasource.password"))
                .withCommand("sleep", "infinity")) {
          client.start();
          var result =
              client.execInContainer(
                  "psql",
                  "-h",
                  "adapter-postgres",
                  "-U",
                  properties.get("quarkus.datasource.username"),
                  "-d",
                  "network_contract",
                  "-Atc",
                  "SELECT current_database()");
          assertEquals(0, result.getExitCode(), result.getStderr());
          assertEquals("network_contract", result.getStdout().trim());
        }
      } finally {
        manager.stop();
        manager.stop();
      }
      assertEquals(
          network.getId(),
          org.testcontainers.DockerClientFactory.instance()
              .client()
              .inspectNetworkCmd()
              .withNetworkId(network.getId())
              .exec()
              .getId());
    }
  }
}
