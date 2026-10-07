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
package com.forwardmeasure.testcontainers.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

class PostgreSqlFixtureContractTest {
  @Test
  void realDatabaseSupportsIdempotentSchemaCreationAndRejectsSqlInjection() throws Exception {
    var postgres = new PostgreSqlTestContainer();
    try (postgres) {
      assertFalse(postgres.isRunning());
      assertThrows(IllegalStateException.class, postgres::hostJdbcUrl);
      postgres.start();
      assertSame(postgres, postgres.start());
      assertTrue(postgres.isRunning());
      assertTrue(postgres.mappedPort() > 0);
      assertFalse(postgres.host().isBlank());
      assertFalse(postgres.containerName().isBlank());
      postgres.createSchema("Contract_Schema");
      postgres.createSchema("Contract_Schema");
      assertThrows(
          IllegalArgumentException.class, () -> postgres.createSchema("x; DROP SCHEMA public"));
      assertThrows(IllegalArgumentException.class, () -> postgres.createSchema("x".repeat(64)));
      try (var connection = postgres.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE \"Contract_Schema\".records (id integer PRIMARY KEY)");
        statement.execute("INSERT INTO \"Contract_Schema\".records VALUES (7)");
        try (var rows =
            statement.executeQuery(
                "SELECT id, current_database(), current_user FROM \"Contract_Schema\".records")) {
          assertTrue(rows.next());
          assertEquals(7, rows.getInt(1));
          assertEquals(postgres.databaseName(), rows.getString(2));
          assertEquals(postgres.username(), rows.getString(3));
        }
      }
      assertThrows(IllegalStateException.class, postgres::networkJdbcUrl);
      assertFalse(postgres.configuration().toString().contains(postgres.password()));
    }
    postgres.close();
    assertFalse(postgres.isRunning());
    assertThrows(IllegalStateException.class, postgres::start);
    assertThrows(IllegalStateException.class, postgres::dataSource);
  }

  @Test
  void callerNetworkAllowsSiblingDatabaseAccessAndSurvivesClosure() throws Exception {
    try (var network = Network.newNetwork()) {
      var defaults = PostgreSqlContainerConfiguration.defaults();
      var config =
          new PostgreSqlContainerConfiguration(
                  defaults.image(),
                  "network_contract",
                  defaults.username(),
                  defaults.password(),
                  Optional.empty(),
                  List.of(),
                  0,
                  0)
              .withNetwork(network.getId(), List.of("fixture-postgres"));
      try (var postgres = new PostgreSqlTestContainer(config).start();
          var client =
              new GenericContainer<>(defaults.image())
                  .withNetwork(network)
                  .withEnv("PGPASSWORD", config.password())
                  .withCommand("sleep", "infinity")) {
        client.start();
        assertEquals(
            "jdbc:postgresql://fixture-postgres:5432/network_contract", postgres.networkJdbcUrl());
        var networkSource = (org.postgresql.ds.PGSimpleDataSource) postgres.networkDataSource();
        assertEquals("fixture-postgres", networkSource.getServerNames()[0]);
        var result =
            client.execInContainer(
                "psql",
                "-h",
                networkSource.getServerNames()[0],
                "-U",
                postgres.username(),
                "-d",
                postgres.databaseName(),
                "-Atc",
                "SELECT 42");
        assertEquals(0, result.getExitCode(), result.getStderr());
        assertEquals("42", result.getStdout().trim());
      }
      assertEquals(
          network.getId(),
          DockerClientFactory.instance()
              .client()
              .inspectNetworkCmd()
              .withNetworkId(network.getId())
              .exec()
              .getId());
    }
  }

  @Test
  void invalidConnectionSettingsAndMemoryLimitsCannotReachDocker() {
    var d = PostgreSqlContainerConfiguration.defaults();
    for (long[] limits : new long[][] {{-1, 0}, {0, -1}, {512, 256}}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new PostgreSqlContainerConfiguration(
                  d.image(),
                  d.databaseName(),
                  d.username(),
                  d.password(),
                  Optional.empty(),
                  List.of(),
                  limits[0],
                  limits[1]));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgreSqlContainerConfiguration(
                d.image(), " ", d.username(), d.password(), Optional.empty(), List.of(), 0, 0));
    assertThrows(IllegalArgumentException.class, () -> d.withNetwork(" ", List.of("alias")));
    assertThrows(IllegalArgumentException.class, () -> d.withNetwork("network", List.of(" ")));
    var aliases = new java.util.ArrayList<>(List.of("original"));
    var config = d.withNetwork("network", aliases);
    aliases.clear();
    assertEquals(List.of("original"), config.networkAliases());
  }
}
