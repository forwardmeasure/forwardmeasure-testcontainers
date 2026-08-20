package com.forwardmeasure.testcontainers.postgresql;

import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Explicitly owned PostgreSQL Testcontainer with host and network addresses. */
public final class PostgreSqlTestContainer implements AutoCloseable {

  private static final Pattern POSTGRESQL_IDENTIFIER =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

  private final PostgreSqlContainerConfiguration configuration;
  private final PostgreSQLContainer container;
  private boolean started;
  private boolean closed;

  public PostgreSqlTestContainer() {
    this(PostgreSqlContainerConfiguration.defaults());
  }

  public PostgreSqlTestContainer(PostgreSqlContainerConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.container = configure(configuration);
  }

  public synchronized PostgreSqlTestContainer start() {
    if (closed) {
      throw new IllegalStateException("PostgreSQL test container has been closed");
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

  public DataSource dataSource() {
    return dataSource(hostJdbcUrl());
  }

  public DataSource networkDataSource() {
    return dataSource(networkJdbcUrl());
  }

  /** Creates a safely quoted schema for integration-test setup. */
  public void createSchema(String schema) {
    Objects.requireNonNull(schema, "schema");
    if (!POSTGRESQL_IDENTIFIER.matcher(schema).matches()) {
      throw new IllegalArgumentException("Invalid PostgreSQL schema identifier: " + schema);
    }
    try (var connection = dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("create schema if not exists \"" + schema + "\"");
    } catch (java.sql.SQLException exception) {
      throw new IllegalStateException(
          "Failed to create PostgreSQL test schema " + schema, exception);
    }
  }

  public String hostJdbcUrl() {
    ensureStarted();
    return container.getJdbcUrl();
  }

  public String networkJdbcUrl() {
    ensureStarted();
    String alias =
        configuration.networkAliases().stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No PostgreSQL container network alias is configured"));
    return "jdbc:postgresql://"
        + alias
        + ':'
        + PostgreSQLContainer.POSTGRESQL_PORT
        + '/'
        + configuration.databaseName();
  }

  public String username() {
    return configuration.username();
  }

  public String password() {
    return configuration.password();
  }

  public String databaseName() {
    return configuration.databaseName();
  }

  public String host() {
    ensureStarted();
    return container.getHost();
  }

  public int mappedPort() {
    ensureStarted();
    return container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
  }

  public String containerName() {
    ensureStarted();
    return container.getContainerName();
  }

  public PostgreSqlContainerConfiguration configuration() {
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

  private PostgreSQLContainer configure(PostgreSqlContainerConfiguration configuration) {
    PostgreSQLContainer configured =
        new PostgreSQLContainer(configuration.image())
            .withDatabaseName(configuration.databaseName())
            .withUsername(configuration.username())
            .withPassword(configuration.password());
    if (configuration.memoryBytes() > 0 || configuration.memorySwapBytes() > 0) {
      configured.withCreateContainerCmdModifier(
          command -> {
            if (configuration.memoryBytes() > 0) {
              command.getHostConfig().withMemory(configuration.memoryBytes());
            }
            if (configuration.memorySwapBytes() > 0) {
              command.getHostConfig().withMemorySwap(configuration.memorySwapBytes());
            }
          });
    }
    configuration
        .networkId()
        .ifPresent(networkId -> configured.withNetwork(existingNetwork(networkId)));
    if (!configuration.networkAliases().isEmpty()) {
      configured.withNetworkAliases(configuration.networkAliases().toArray(String[]::new));
    }
    return configured;
  }

  /**
   * Refers to a caller-owned Docker network without assuming ownership of its lifecycle.
   * Testcontainers uses this reference to create an endpoint with DNS aliases; closing the
   * PostgreSQL fixture must not remove the network.
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

  private DataSource dataSource(String jdbcUrl) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(jdbcUrl);
    dataSource.setUser(username());
    dataSource.setPassword(password());
    return dataSource;
  }

  private void ensureStarted() {
    if (!started) {
      throw new IllegalStateException("PostgreSQL test container has not been started");
    }
  }
}
