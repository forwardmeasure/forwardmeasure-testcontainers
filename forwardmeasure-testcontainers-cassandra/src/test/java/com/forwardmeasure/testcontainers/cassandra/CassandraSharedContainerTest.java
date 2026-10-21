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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** JVM-wide shared fixtures and the per-test-class keyspaces that make sharing them safe. */
class CassandraSharedContainerTest {

  @Test
  void aSharedFixtureIsOneRunningInstancePerConfigurationThatTestsCannotClose() {
    var shared = CassandraTestContainers.shared(CassandraContainerConfiguration.defaults());
    String name = shared.containerName();
    assertTrue(shared.isShared());
    assertTrue(shared.isRunning());
    // An equal but distinct configuration value finds the same instance: the key is the value.
    assertSame(
        shared,
        CassandraTestContainers.shared(
            CassandraContainerConfiguration.defaults().withHeap("512M", "100M")));

    // A test closing it - directly or through try-with-resources - must not stop it for others.
    shared.close();
    try (var borrowed =
        CassandraTestContainers.shared(CassandraContainerConfiguration.defaults())) {
      assertSame(shared, borrowed);
    }
    assertTrue(shared.isRunning());
    assertEquals(name, shared.containerName());
    try (var session = session(shared, false)) {
      assertEquals(
          "org.apache.cassandra.dht.Murmur3Partitioner",
          session.execute("SELECT partitioner FROM system.local").one().getString(0));
    }
    assertSame(shared, shared.start());
    assertThrows(IllegalStateException.class, shared::withPasswordAuthentication);
    // Owned fixtures are unaffected: they still close.
    var owned = new CassandraTestContainer();
    assertFalse(owned.isShared());
    owned.close();
    assertThrows(IllegalStateException.class, owned::start);
  }

  @Test
  void concurrentFirstCallersShareOneStartAndDistinctConfigurationsGetDistinctFixtures()
      throws Exception {
    // A configuration no other test uses, so this test performs its first, lazy start.
    var configuration = CassandraContainerConfiguration.defaults().withPasswordAuthentication(true);
    int callers = 6;
    var ready = new CountDownLatch(callers);
    List<Future<CassandraTestContainer>> results = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        Callable<CassandraTestContainer> call =
            () -> {
              ready.countDown();
              ready.await();
              return CassandraTestContainers.shared(configuration);
            };
        results.add(executor.submit(call));
      }
      var first = results.get(0).get();
      for (var result : results) {
        assertSame(first, result.get(), "Every concurrent caller must get the one shared fixture");
      }
      var unauthenticated =
          CassandraTestContainers.shared(CassandraContainerConfiguration.defaults());
      assertNotSame(unauthenticated, first);
      assertNotEquals(unauthenticated.containerName(), first.containerName());
      assertTrue(first.configuration().passwordAuthentication());

      // The keyspace helpers authenticate as the superuser on an authenticated shared fixture.
      var keyspace = first.createUniqueKeyspace("authenticated_shared");
      try (var admin = session(first, true)) {
        assertTrue(keyspaceExists(admin, keyspace.name()));
        first.dropKeyspace(keyspace.name());
        assertFalse(keyspaceExists(admin, keyspace.name()));
      }
    }
  }

  @Test
  void uniqueKeyspacesAreValidDistinctIsolatedAndDroppable() {
    var cassandra = CassandraTestContainers.shared(CassandraContainerConfiguration.defaults());
    var first = cassandra.createUniqueKeyspace("Itineris-Graph Test");
    var second = cassandra.createUniqueKeyspace("Itineris-Graph Test");
    var longName = cassandra.createUniqueKeyspace("A".repeat(80) + "ConfigurationTest");
    var digits = cassandra.createUniqueKeyspace("2024 suite");
    Set<String> names =
        new HashSet<>(List.of(first.name(), second.name(), longName.name(), digits.name()));
    assertEquals(4, names.size(), "Keyspace names must be unique: " + names);
    for (String name : names) {
      assertTrue(name.matches("[a-z][a-z0-9_]{0,47}"), name);
    }
    assertTrue(first.name().startsWith("itineris_graph_test_"), first.name());
    assertEquals(CassandraKeyspace.MAX_NAME_LENGTH, longName.name().length());
    assertTrue(digits.name().startsWith("k2024_suite_"), digits.name());

    try (var session = session(cassandra, false)) {
      for (String name : names) {
        assertTrue(keyspaceExists(session, name), name);
        assertEquals(
            "org.apache.cassandra.locator.SimpleStrategy",
            session
                .execute(
                    "SELECT replication FROM system_schema.keyspaces WHERE keyspace_name = ?", name)
                .one()
                .getMap("replication", String.class, String.class)
                .get("class"));
      }
      // Same table name in two keyspaces: data written to one is invisible in the other.
      for (var keyspace : List.of(first, second)) {
        session.execute("CREATE TABLE " + keyspace.name() + ".vertex (id text PRIMARY KEY)");
      }
      session.execute("INSERT INTO " + first.name() + ".vertex (id) VALUES ('only-in-first')");
      assertEquals(1, session.execute("SELECT id FROM " + first.name() + ".vertex").all().size());
      assertEquals(0, session.execute("SELECT id FROM " + second.name() + ".vertex").all().size());

      for (String name : names) {
        cassandra.dropKeyspace(name);
        assertFalse(keyspaceExists(session, name), name);
      }
      // Dropping again is harmless, so cleanup can run after a partial failure.
      cassandra.dropKeyspace(first.name());
      assertThrows(
          InvalidQueryException.class,
          () -> session.execute("SELECT id FROM " + first.name() + ".vertex"));
    }
  }

  @Test
  void keyspaceHelpersRejectUnsafeNamesAndUnstartedFixtures() {
    var cassandra = CassandraTestContainers.shared(CassandraContainerConfiguration.defaults());
    for (String name : new String[] {"system", "system_schema", "system_auth"}) {
      assertThrows(IllegalArgumentException.class, () -> cassandra.dropKeyspace(name), name);
    }
    for (String name : new String[] {"Mixed", "a; DROP KEYSPACE system", "a".repeat(49), ""}) {
      assertThrows(IllegalArgumentException.class, () -> cassandra.dropKeyspace(name), name);
    }
    assertThrows(NullPointerException.class, () -> cassandra.dropKeyspace(null));
    assertThrows(IllegalArgumentException.class, () -> cassandra.createUniqueKeyspace("  "));
    assertThrows(NullPointerException.class, () -> cassandra.createUniqueKeyspace(null));
    // The system keyspaces survived the attempts.
    try (var session = session(cassandra, false)) {
      assertTrue(keyspaceExists(session, "system_schema"));
    }

    var unstarted = new CassandraTestContainer();
    assertThrows(IllegalStateException.class, () -> unstarted.createUniqueKeyspace("early"));
    assertThrows(IllegalStateException.class, () -> unstarted.dropKeyspace("early"));
    assertThrows(IllegalStateException.class, unstarted::networkContactPoint);
    unstarted.close();
    assertThrows(IllegalStateException.class, () -> unstarted.createUniqueKeyspace("late"));
  }

  private static boolean keyspaceExists(CqlSession session, String name) {
    return session
            .execute(
                "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?", name)
            .one()
        != null;
  }

  private static CqlSession session(CassandraTestContainer cassandra, boolean superuser) {
    var builder =
        CqlSession.builder()
            .addContactPoint(cassandra.contactPoint())
            .withLocalDatacenter(cassandra.localDatacenter());
    if (superuser) {
      builder.withAuthCredentials(cassandra.superuserName(), cassandra.superuserPassword());
    }
    return builder.build();
  }
}
