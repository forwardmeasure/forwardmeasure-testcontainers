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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import io.quarkus.test.common.DevServicesContext;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;

/** Drives the Quarkus lifecycle manager directly against real Cassandra containers. */
class CassandraTestResourceLifecycleManagerIntegrationTest {

  @WithCassandraTestContainer(keyspaceProperty = "app.cassandra.keyspace")
  static class DefaultProperties {}

  @WithCassandraTestContainer(
      passwordAuthentication = true,
      shared = true,
      contactPointsProperty = "itineris.cql.hosts",
      localDatacenterProperty = "itineris.cql.dc",
      usernameProperty = "itineris.cql.user",
      passwordProperty = "itineris.cql.secret",
      keyspaceProperty = "itineris.cql.keyspace",
      keyspacePrefix = "Itineris Graph")
  static class SharedAuthenticated {}

  @WithCassandraTestContainer(useNetworkContactPoint = true, networkAlias = "adapter-cassandra")
  static class NetworkContactPoint {}

  @WithCassandraTestContainer(contactPointsProperty = " ")
  static class NoContactPointProperty {}

  @WithCassandraTestContainer(configurationFile = "cassandra/missing.yaml")
  static class MissingConfigurationFile {}

  @Test
  void defaultPropertiesConnectToAnOwnedContainerThatStopCloses() {
    var manager = new CassandraTestResourceLifecycleManager();
    manager.init(DefaultProperties.class.getAnnotation(WithCassandraTestContainer.class));
    Map<String, String> properties = manager.start();
    InetSocketAddress contactPoint;
    try {
      // Exactly the configured keys - no credentials without authentication, nothing extra.
      assertEquals(
          Set.of(
              "quarkus.cassandra.contact-points",
              "quarkus.cassandra.local-datacenter",
              "app.cassandra.keyspace"),
          properties.keySet());
      assertThrows(UnsupportedOperationException.class, () -> properties.put("x", "y"));
      contactPoint = contactPoint(properties.get("quarkus.cassandra.contact-points"));
      // A session built only from the emitted values works, in the emitted keyspace.
      try (var session =
          CqlSession.builder()
              .addContactPoint(contactPoint)
              .withLocalDatacenter(properties.get("quarkus.cassandra.local-datacenter"))
              .withKeyspace(properties.get("app.cassandra.keyspace"))
              .build()) {
        assertTrue(properties.get("app.cassandra.keyspace").startsWith("quarkus_test_"));
        session.execute("CREATE TABLE probe (id text PRIMARY KEY)");
        session.execute("INSERT INTO probe (id) VALUES ('written')");
        assertEquals("written", session.execute("SELECT id FROM probe").one().getString(0));
      }
      assertThrows(IllegalStateException.class, manager::start);
    } finally {
      manager.stop();
      manager.stop();
    }
    // The owned container is gone once the resource stops.
    var stopped = contactPoint;
    assertThrows(
        AllNodesFailedException.class,
        () ->
            CqlSession.builder()
                .addContactPoint(stopped)
                .withLocalDatacenter("datacenter1")
                .build()
                .close());
  }

  @Test
  void customPropertyNamesCarryCredentialsAndSharedResourcesOutliveEachOther() {
    var annotation = SharedAuthenticated.class.getAnnotation(WithCassandraTestContainer.class);
    var first = new CassandraTestResourceLifecycleManager();
    var second = new CassandraTestResourceLifecycleManager();
    first.init(annotation);
    second.init(annotation);
    Map<String, String> firstProperties = first.start();
    try {
      Map<String, String> secondProperties = second.start();
      try {
        assertEquals(
            Set.of(
                "itineris.cql.hosts",
                "itineris.cql.dc",
                "itineris.cql.user",
                "itineris.cql.secret",
                "itineris.cql.keyspace"),
            firstProperties.keySet());
        // One shared container, one keyspace per resource.
        assertEquals(
            firstProperties.get("itineris.cql.hosts"), secondProperties.get("itineris.cql.hosts"));
        assertNotEquals(
            firstProperties.get("itineris.cql.keyspace"),
            secondProperties.get("itineris.cql.keyspace"));
        assertTrue(firstProperties.get("itineris.cql.keyspace").startsWith("itineris_graph_"));

        // Without the emitted credentials the cluster refuses the session.
        assertThrows(
            AllNodesFailedException.class, () -> builder(firstProperties, false).build().close());
        try (var session = builder(firstProperties, true).build()) {
          assertTrue(keyspaceExists(session, firstProperties.get("itineris.cql.keyspace")));
          first.stop();
          // Stopping one resource drops its keyspace but leaves the shared container running.
          assertFalse(keyspaceExists(session, firstProperties.get("itineris.cql.keyspace")));
          assertTrue(keyspaceExists(session, secondProperties.get("itineris.cql.keyspace")));
        }
        second.stop();
        try (var session = builder(secondProperties, true).build()) {
          assertFalse(keyspaceExists(session, secondProperties.get("itineris.cql.keyspace")));
        }
      } finally {
        second.stop();
      }
    } finally {
      first.stop();
    }
  }

  @Test
  void incompleteConfigurationFailsBeforeStartingAContainer() {
    var manager = new CassandraTestResourceLifecycleManager();
    manager.stop();
    assertThrows(IllegalStateException.class, manager::start);
    // A network contact point needs a Dev Services network: fail closed rather than emit an
    // address the application cannot reach.
    manager.init(NetworkContactPoint.class.getAnnotation(WithCassandraTestContainer.class));
    assertThrows(IllegalStateException.class, manager::start);
    manager.init(NoContactPointProperty.class.getAnnotation(WithCassandraTestContainer.class));
    assertThrows(IllegalStateException.class, manager::start);
    manager.init(MissingConfigurationFile.class.getAnnotation(WithCassandraTestContainer.class));
    assertThrows(IllegalArgumentException.class, manager::start);
    manager.stop();
  }

  @Test
  void devServicesNetworkContactPointIsUsableAndTheNetworkRemainsCallerOwned() {
    try (var network = Network.newNetwork()) {
      var manager = new CassandraTestResourceLifecycleManager();
      manager.init(NetworkContactPoint.class.getAnnotation(WithCassandraTestContainer.class));
      manager.setIntegrationTestContext(
          new DevServicesContext() {
            @Override
            public Map<String, String> devServicesProperties() {
              return Map.of();
            }

            @Override
            public Optional<String> containerNetworkId() {
              return Optional.of(network.getId());
            }
          });
      try {
        var properties = manager.start();
        assertEquals("adapter-cassandra:9042", properties.get("quarkus.cassandra.contact-points"));
        // A peer on the Dev Services network reaches Cassandra at the emitted address.
        try (GenericContainer<?> peer =
            new GenericContainer<>("cassandra:5.0.5")
                .withNetwork(network)
                .withCommand(
                    "cqlsh",
                    "adapter-cassandra",
                    "9042",
                    "-e",
                    "SELECT data_center FROM system.local")
                .withStartupCheckStrategy(
                    new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
          peer.start();
          assertEquals(0L, peer.getCurrentContainerInfo().getState().getExitCodeLong());
          assertTrue(
              peer.getLogs().contains(properties.get("quarkus.cassandra.local-datacenter")),
              peer.getLogs());
        }
      } finally {
        manager.stop();
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

  private static CqlSessionBuilder builder(Map<String, String> properties, boolean credentials) {
    var builder =
        CqlSession.builder()
            .addContactPoint(contactPoint(properties.get("itineris.cql.hosts")))
            .withLocalDatacenter(properties.get("itineris.cql.dc"));
    if (credentials) {
      builder.withAuthCredentials(
          properties.get("itineris.cql.user"), properties.get("itineris.cql.secret"));
    }
    return builder;
  }

  private static boolean keyspaceExists(CqlSession session, String name) {
    return session
            .execute(
                "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?", name)
            .one()
        != null;
  }

  private static InetSocketAddress contactPoint(String hostAndPort) {
    int colon = hostAndPort.lastIndexOf(':');
    return new InetSocketAddress(
        hostAndPort.substring(0, colon), Integer.parseInt(hostAndPort.substring(colon + 1)));
  }
}
