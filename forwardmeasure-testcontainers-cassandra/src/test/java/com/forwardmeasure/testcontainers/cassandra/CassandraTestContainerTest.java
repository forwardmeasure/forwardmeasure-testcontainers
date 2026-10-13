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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastax.oss.driver.api.core.CqlSession;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;

class CassandraTestContainerTest {
  @Test
  void persistsAcrossIndependentClientSessionsAndOwnsItsLifecycle() {
    try (var cassandra = new CassandraTestContainer()) {
      assertThrows(IllegalStateException.class, cassandra::contactPoint);
      assertThrows(IllegalStateException.class, cassandra::localDatacenter);
      assertSame(cassandra, cassandra.start());
      assertSame(cassandra, cassandra.start());
      try (var session =
          CqlSession.builder()
              .addContactPoint(cassandra.contactPoint())
              .withLocalDatacenter(cassandra.localDatacenter())
              .build()) {
        session.execute(
            "CREATE KEYSPACE fixture_contract WITH replication = {'class': 'SimpleStrategy',"
                + " 'replication_factor': 1}");
        session.execute("CREATE TABLE fixture_contract.probe (id text PRIMARY KEY, value text)");
        session.execute(
            "INSERT INTO fixture_contract.probe (id, value) VALUES ('saved',"
                + " 'survives-client-reconnect')");
      }
      try (var session =
          CqlSession.builder()
              .addContactPoint(cassandra.contactPoint())
              .withLocalDatacenter(cassandra.localDatacenter())
              .build()) {
        assertEquals(
            "survives-client-reconnect",
            session
                .execute("SELECT value FROM fixture_contract.probe WHERE id = 'saved'")
                .one()
                .getString("value"));
      }
      cassandra.close();
      assertThrows(IllegalStateException.class, cassandra::contactPoint);
      assertThrows(IllegalStateException.class, cassandra::start);
    }
  }

  @Test
  void aNetworkedFixtureIsReachableByItsAliasFromAnotherContainer() {
    try (Network network = Network.newNetwork();
        var cassandra = new CassandraTestContainer(network, "cassandra-fixture").start();
        // A real peer on the same network: cqlsh exits 0 only once it has queried the alias.
        GenericContainer<?> peer =
            new GenericContainer<>("cassandra:5.0.5")
                .withNetwork(network)
                .withCommand(
                    "cqlsh", "cassandra-fixture", "-e", "SELECT release_version FROM system.local")
                .withStartupCheckStrategy(
                    new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
      peer.start();
      assertEquals(0L, peer.getCurrentContainerInfo().getState().getExitCodeLong());
      // Still reachable from the host as well.
      try (var session =
          CqlSession.builder()
              .addContactPoint(cassandra.contactPoint())
              .withLocalDatacenter(cassandra.localDatacenter())
              .build()) {
        assertEquals(1, session.execute("SELECT release_version FROM system.local").all().size());
      }
    }
  }

  @Test
  void aNetworkedFixtureNeedsANetworkAndAnAlias() {
    try (Network network = Network.newNetwork()) {
      assertThrows(NullPointerException.class, () -> new CassandraTestContainer(null, "alias"));
      assertThrows(IllegalArgumentException.class, () -> new CassandraTestContainer(network, null));
      assertThrows(IllegalArgumentException.class, () -> new CassandraTestContainer(network, " "));
    }
  }

  @Test
  void aFailedStartClosesTheFixtureAndRethrows() {
    // A network Docker has never heard of: attaching the container to it fails the start.
    String missing = "fm-missing-network-" + UUID.randomUUID();
    Network absent =
        new Network() {
          @Override
          public String getId() {
            return missing;
          }

          @Override
          public void close() {}
        };
    var cassandra = new CassandraTestContainer(absent, "cassandra-gone");
    assertThrows(RuntimeException.class, cassandra::start);
    // The failure closed it: it can't be restarted or used.
    IllegalStateException closed = assertThrows(IllegalStateException.class, cassandra::start);
    assertEquals("Cassandra test container has been closed", closed.getMessage());
    assertThrows(IllegalStateException.class, cassandra::contactPoint);
    cassandra.close();
  }

  @Test
  void closingAnUnstartedFixtureDoesNotStartInfrastructure() {
    var cassandra = new CassandraTestContainer();
    cassandra.close();
    cassandra.close();
    assertThrows(IllegalStateException.class, cassandra::start);
    assertThrows(IllegalStateException.class, cassandra::localDatacenter);
  }
}
