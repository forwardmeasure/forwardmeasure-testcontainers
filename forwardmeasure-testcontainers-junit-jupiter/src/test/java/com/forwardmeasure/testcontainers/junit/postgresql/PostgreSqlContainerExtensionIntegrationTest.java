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
package com.forwardmeasure.testcontainers.junit.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

@WithPostgreSqlContainer(databaseName = "junit_contract")
class PostgreSqlContainerExtensionIntegrationTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(PostgreSqlContainerExtensionIntegrationTest.class);

  @Test
  void startsRealPostgresAndInjectsTheOwnedContainer(PostgreSqlTestContainer container)
      throws Exception {
    LOGGER.info(
        "@WithPostgreSqlContainer injected {} - jdbc url {}",
        container.containerName(),
        container.hostJdbcUrl());
    container.createSchema("junit_fixture");
    assertFalse(container.configuration().toString().contains(container.password()));
    assertThrows(IllegalArgumentException.class, () -> container.createSchema("unsafe; schema"));
    try (var connection = container.dataSource().getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery("select current_database(), current_user")) {
      assertTrue(container.isRunning());
      assertTrue(result.next());
      LOGGER.info("current_database={} current_user={}", result.getString(1), result.getString(2));
      assertEquals("junit_contract", result.getString(1));
      assertEquals("forwardmeasure", result.getString(2));
    }
    try (var connection = container.dataSource().getConnection();
        var statement =
            connection.prepareStatement(
                "select exists (select 1 from information_schema.schemata"
                    + " where schema_name = ?)")) {
      statement.setString(1, "junit_fixture");
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
        assertTrue(result.getBoolean(1));
      }
    }
  }

  @Test
  void exposesPostgresThroughItsConfiguredContainerNetworkAlias() throws Exception {
    try (Network network = Network.newNetwork();
        PostgreSqlTestContainer database =
            new PostgreSqlTestContainer(
                    PostgreSqlContainerConfiguration.defaults()
                        .withNetwork(network.getId(), List.of("contract-postgres")))
                .start();
        GenericContainer<?> client =
            new GenericContainer<>(DockerImageName.parse("postgres:18-alpine"))
                .withNetwork(network)
                .withEnv("PGPASSWORD", database.password())
                .withCommand("sleep", "60")) {
      client.start();
      LOGGER.info(
          "Client container {} joined network alias contract-postgres, target url {}",
          client.getContainerName(),
          database.networkJdbcUrl());

      var result =
          client.execInContainer(
              "psql",
              "-h",
              "contract-postgres",
              "-U",
              database.username(),
              "-d",
              database.databaseName(),
              "-tAc",
              "select 1");
      LOGGER.info("psql exit={} stdout={}", result.getExitCode(), result.getStdout().trim());

      assertEquals(0, result.getExitCode(), result.getStderr());
      assertEquals("1", result.getStdout().trim());
      assertTrue(database.networkJdbcUrl().contains("contract-postgres"));
    }
  }
}
