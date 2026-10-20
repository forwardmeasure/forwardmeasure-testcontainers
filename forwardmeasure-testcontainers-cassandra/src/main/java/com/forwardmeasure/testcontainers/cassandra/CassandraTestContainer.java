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
package com.forwardmeasure.testcontainers.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.servererrors.AlreadyExistsException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * Explicitly owned single-node Cassandra fixture for real persistence contracts.
 *
 * <p>A fixture is either owned by its creator, who must {@link #close()} it, or shared JVM-wide
 * through {@link CassandraTestContainers#shared(CassandraContainerConfiguration)}. A shared fixture
 * ignores {@link #close()} - no single test owns it - and is stopped by a JVM shutdown hook (and,
 * failing that, by Testcontainers' Ryuk). Tests sharing one fixture isolate their data with {@link
 * #createUniqueKeyspace(String)}.
 */
public final class CassandraTestContainer implements AutoCloseable {

  public static final int CQL_PORT = 9042;

  static final String CONFIGURATION_PATH = "/etc/cassandra/cassandra.yaml";

  private static final Set<String> SYSTEM_KEYSPACES =
      Set.of(
          "system",
          "system_auth",
          "system_distributed",
          "system_schema",
          "system_traces",
          "system_views",
          "system_virtual_schema");

  private static final int UNIQUE_SUFFIX_LENGTH = 12;

  private static final int MAX_PREFIX_LENGTH =
      CassandraKeyspace.MAX_NAME_LENGTH - 1 - UNIQUE_SUFFIX_LENGTH;

  private static final Duration ADMIN_REQUEST_TIMEOUT = Duration.ofSeconds(60);

  private static final SecureRandom RANDOM = new SecureRandom();

  private volatile CassandraContainerConfiguration configuration;
  private final CassandraContainer container;
  private volatile boolean started;
  private volatile boolean closed;
  private volatile boolean shared;
  private CqlSession adminSession;

  public CassandraTestContainer() {
    this(CassandraContainerConfiguration.defaults());
  }

  public CassandraTestContainer(CassandraContainerConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.container = configure(configuration);
  }

  /** Attaches Cassandra to the caller's disposable network for real service-to-database traffic. */
  public CassandraTestContainer(Network network, String alias) {
    this(networked(network, alias));
  }

  /**
   * Turns on Cassandra's own password authentication and role-based authorization (as k8ssandra
   * clusters run), with the default {@code cassandra}/{@code cassandra} superuser - for contracts
   * that create roles and grant them keyspaces. Call before {@link #start()}. Equivalent to {@link
   * CassandraContainerConfiguration#withPasswordAuthentication(boolean)}.
   */
  public synchronized CassandraTestContainer withPasswordAuthentication() {
    if (started || closed)
      throw new IllegalStateException("Authentication must be configured before start");
    if (!configuration.passwordAuthentication()) {
      configuration = configuration.withPasswordAuthentication(true);
      configureStartup(container, configuration);
    }
    return this;
  }

  /** The superuser {@link #withPasswordAuthentication()} clusters start with. */
  public String superuserName() {
    return container.getUsername();
  }

  public String superuserPassword() {
    return container.getPassword();
  }

  public synchronized CassandraTestContainer start() {
    if (closed) throw new IllegalStateException("Cassandra test container has been closed");
    if (!started) {
      try {
        container.start();
        started = true;
        if (configuration.tls().isPresent()) {
          awaitEncryptedCql();
        }
      } catch (RuntimeException | Error failure) {
        release();
        throw failure;
      }
    }
    return this;
  }

  public InetSocketAddress contactPoint() {
    ensureStarted();
    return new InetSocketAddress(container.getHost(), container.getMappedPort(CQL_PORT));
  }

  /**
   * The CQL address other containers on the configured network use: the first network alias and the
   * container's own CQL port, unresolved (it only resolves inside Docker's network).
   */
  public InetSocketAddress networkContactPoint() {
    ensureStarted();
    String alias =
        configuration.networkAliases().stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No Cassandra container network alias is configured"));
    return InetSocketAddress.createUnresolved(alias, CQL_PORT);
  }

  public String localDatacenter() {
    ensureStarted();
    return container.getLocalDatacenter();
  }

  public String containerName() {
    ensureStarted();
    return container.getContainerName();
  }

  public CassandraContainerConfiguration configuration() {
    return configuration;
  }

  /** Whether this fixture is a JVM-wide shared one, which {@link #close()} leaves running. */
  public boolean isShared() {
    return shared;
  }

  public boolean isRunning() {
    return started && !closed && container.isRunning();
  }

  /**
   * Creates a fresh keyspace ({@code SimpleStrategy}, replication factor 1) whose name starts with
   * {@code prefix} and ends with a random suffix, so test classes sharing one container never see
   * each other's data. The prefix is normalised to a valid unquoted name: lower-cased, every
   * character outside {@code [a-z0-9_]} replaced by {@code _}, prefixed with {@code k} if it does
   * not start with a letter, and truncated so the whole name fits Cassandra's 48 characters.
   * Creation fails rather than reuse an existing keyspace. Uses the superuser when password
   * authentication is on.
   *
   * @throws IllegalArgumentException if {@code prefix} is blank
   * @throws IllegalStateException if the fixture is not running
   */
  public CassandraKeyspace createUniqueKeyspace(String prefix) {
    String normalised = normalisePrefix(prefix);
    CqlSession session = adminSession();
    AlreadyExistsException collision = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      CassandraKeyspace keyspace = new CassandraKeyspace(normalised + '_' + uniqueSuffix());
      try {
        session.execute(
            "CREATE KEYSPACE "
                + keyspace.name()
                + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
        return keyspace;
      } catch (AlreadyExistsException exists) {
        collision = exists;
      }
    }
    throw new IllegalStateException("Could not find an unused keyspace name", collision);
  }

  /**
   * Drops the keyspace if it exists. Accepts only unquoted lower-case names (as {@link
   * #createUniqueKeyspace(String)} makes) and refuses Cassandra's system keyspaces.
   *
   * @throws IllegalArgumentException if {@code name} is not such a name, or names a system keyspace
   * @throws IllegalStateException if the fixture is not running
   */
  public void dropKeyspace(String name) {
    CassandraKeyspace keyspace = new CassandraKeyspace(name);
    if (SYSTEM_KEYSPACES.contains(keyspace.name())) {
      throw new IllegalArgumentException("Refusing to drop system keyspace " + name);
    }
    adminSession().execute("DROP KEYSPACE IF EXISTS " + keyspace.name());
  }

  /**
   * Stops and removes the container, unless this fixture is {@link #isShared() shared}: then this
   * is a no-op, because no single test owns a shared fixture and closing it would break every other
   * test class using it. The JVM shutdown hook installed by {@link CassandraTestContainers} stops
   * shared fixtures.
   */
  @Override
  public synchronized void close() {
    if (shared) return;
    release();
  }

  void markShared() {
    shared = true;
  }

  /** The real close, used for owned fixtures, failed starts and the shared-fixture shutdown. */
  synchronized void release() {
    if (closed) return;
    closed = true;
    try {
      if (adminSession != null) adminSession.close();
    } finally {
      adminSession = null;
      container.close();
      started = false;
    }
  }

  private synchronized CqlSession adminSession() {
    ensureStarted();
    if (adminSession == null) {
      var loader =
          DriverConfigLoader.programmaticBuilder()
              .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, ADMIN_REQUEST_TIMEOUT)
              .withDuration(
                  DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, ADMIN_REQUEST_TIMEOUT);
      configuration.tls().ifPresent(tls -> tls.configureClient(loader, true));
      CqlSessionBuilder builder =
          CqlSession.builder()
              .addContactPoint(contactPoint())
              .withLocalDatacenter(localDatacenter())
              .withConfigLoader(loader.build());
      if (configuration.passwordAuthentication()) {
        builder.withAuthCredentials(superuserName(), superuserPassword());
      }
      adminSession = builder.build();
    }
    return adminSession;
  }

  private void ensureStarted() {
    if (!started || closed)
      throw new IllegalStateException("Cassandra test container is not running");
  }

  static String normalisePrefix(String prefix) {
    Objects.requireNonNull(prefix, "prefix");
    if (prefix.isBlank()) throw new IllegalArgumentException("prefix must not be blank");
    String normalised = prefix.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    char first = normalised.charAt(0);
    if (first < 'a' || first > 'z') {
      normalised = "k" + normalised;
    }
    return normalised.length() > MAX_PREFIX_LENGTH
        ? normalised.substring(0, MAX_PREFIX_LENGTH)
        : normalised;
  }

  private static String uniqueSuffix() {
    byte[] bytes = new byte[UNIQUE_SUFFIX_LENGTH / 2];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static CassandraContainerConfiguration networked(Network network, String alias) {
    Objects.requireNonNull(network, "network");
    if (alias == null || alias.isBlank()) throw new IllegalArgumentException("alias is required");
    return CassandraContainerConfiguration.defaults().withNetwork(network.getId(), List.of(alias));
  }

  private static CassandraContainer configure(CassandraContainerConfiguration settings) {
    CassandraContainer configured =
        new CassandraContainer(settings.image().asCompatibleSubstituteFor("cassandra"))
            .withEnv("MAX_HEAP_SIZE", settings.maxHeapSize())
            .withEnv("HEAP_NEWSIZE", settings.heapNewSize())
            .withStartupTimeout(settings.startupTimeout());
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
    // Copied in before the container starts, so the entrypoint (and the password-authentication
    // edit below) operate on the caller's file. Resolving it here fails fast on a missing resource.
    settings
        .configurationFile()
        .ifPresent(
            resource ->
                configured.withCopyFileToContainer(
                    MountableFile.forClasspathResource(
                        resource.startsWith("/") ? resource.substring(1) : resource),
                    CONFIGURATION_PATH));
    settings
        .tls()
        .ifPresent(
            tls -> {
              // The upstream Cassandra wait strategy opens plaintext CQL and cannot initialize
              // TLS-only
              // or mutual-TLS clusters. Wait for the socket, then authenticate with our real driver
              // below.
              configured.waitingFor(
                  Wait.forListeningPort().withStartupTimeout(settings.startupTimeout()));
              configured.withCopyFileToContainer(
                  MountableFile.forHostPath(tls.serverKeyStore().file(), 0444),
                  "/etc/cassandra/fixture-server.jks");
              configured.withCopyFileToContainer(
                  MountableFile.forHostPath(tls.serverTrustStore().file(), 0444),
                  "/etc/cassandra/fixture-server-trust.jks");
              String yaml =
                  "client_encryption_options:\n  enabled: true\n  optional: false\n"
                      + "  store_type: JKS\n  keystore: /etc/cassandra/fixture-server.jks\n"
                      + "  keystore_password: "
                      + yamlString(tls.serverKeyStore().password())
                      + "\n"
                      + "  truststore: /etc/cassandra/fixture-server-trust.jks\n"
                      + "  truststore_password: "
                      + yamlString(tls.serverTrustStore().password())
                      + "\n"
                      + "  require_client_auth: "
                      + tls.requireClientAuthentication()
                      + "\n";
              configured.withCopyToContainer(
                  Transferable.of(yaml, 0444), "/etc/cassandra/fixture-tls.yaml");
            });
    configureStartup(configured, settings);
    settings.networkId().ifPresent(networkId -> configured.withNetwork(existingNetwork(networkId)));
    if (!settings.networkAliases().isEmpty()) {
      configured.withNetworkAliases(settings.networkAliases().toArray(String[]::new));
    }
    return configured;
  }

  private static void configureStartup(
      CassandraContainer container, CassandraContainerConfiguration settings) {
    if (!settings.passwordAuthentication() && settings.tls().isEmpty()) {
      return;
    }
    String edits =
        settings.tls().isEmpty()
            ? ""
            : "awk '/^client_encryption_options:/ {skip=1; next} skip && /^[^[:space:]#]/ {skip=0}"
                + " !skip {print}' "
                + CONFIGURATION_PATH
                + " > /tmp/fixture-cassandra.yaml && cat /etc/cassandra/fixture-tls.yaml >>"
                + " /tmp/fixture-cassandra.yaml && cat /tmp/fixture-cassandra.yaml > "
                + CONFIGURATION_PATH
                + " && ";
    if (settings.passwordAuthentication()) {
      edits +=
          "sed -i -e 's/AllowAllAuthenticator/PasswordAuthenticator/'"
              + " -e 's/AllowAllAuthorizer/CassandraAuthorizer/' "
              + CONFIGURATION_PATH
              + " && ";
    }
    String startup = edits + "exec docker-entrypoint.sh cassandra -f";
    container.withCreateContainerCmdModifier(
        command -> command.withEntrypoint("sh", "-c").withCmd(startup));
  }

  private static String yamlString(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private void awaitEncryptedCql() {
    long deadline = System.nanoTime() + configuration.startupTimeout().toNanos();
    RuntimeException lastFailure;
    do {
      try {
        adminSession().execute("SELECT release_version FROM system.local");
        return;
      } catch (RuntimeException notReady) {
        lastFailure = notReady;
        if (adminSession != null) {
          adminSession.close();
          adminSession = null;
        }
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted waiting for encrypted Cassandra CQL", interrupted);
      }
    } while (System.nanoTime() < deadline);
    throw new IllegalStateException(
        "Cassandra did not accept encrypted authenticated CQL", lastFailure);
  }

  /**
   * Refers to a caller-owned Docker network without assuming ownership of its lifecycle, as the
   * PostgreSQL and OpenSearch fixtures do: closing the fixture must not remove the network.
   */
  private static Network existingNetwork(String networkId) {
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
}
