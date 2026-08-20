package com.forwardmeasure.testcontainers.junit.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

@WithPostgreSqlContainer(databaseName = "junit_contract")
class PostgreSqlContainerExtensionIntegrationTest {

  @Test
  void startsRealPostgresAndInjectsTheOwnedContainer(PostgreSqlTestContainer container)
      throws Exception {
    container.createSchema("junit_fixture");
    assertFalse(container.configuration().toString().contains(container.password()));
    assertThrows(IllegalArgumentException.class, () -> container.createSchema("unsafe; schema"));
    try (var connection = container.dataSource().getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery("select current_database(), current_user")) {
      assertTrue(container.isRunning());
      assertTrue(result.next());
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

      assertEquals(0, result.getExitCode(), result.getStderr());
      assertEquals("1", result.getStdout().trim());
      assertTrue(database.networkJdbcUrl().contains("contract-postgres"));
    }
  }
}
