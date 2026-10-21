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
package com.forwardmeasure.testcontainers.junit.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastax.oss.driver.api.core.CqlSession;
import com.forwardmeasure.testcontainers.cassandra.CassandraContainerConfiguration;
import com.forwardmeasure.testcontainers.cassandra.CassandraKeyspace;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainer;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@WithCassandraContainer(shared = true, keyspacePerClass = true)
class CassandraContainerExtensionIntegrationTest {

  private static CassandraKeyspace keyspaceSeenBeforeAll;

  @BeforeAll
  static void lookupsAreReadyBeforeTheClassRuns() {
    // What Spring's @DynamicPropertySource or Micronaut's TestPropertyProvider would call.
    keyspaceSeenBeforeAll =
        CassandraContainerExtension.keyspaceFor(CassandraContainerExtensionIntegrationTest.class);
    assertTrue(
        CassandraContainerExtension.containerFor(CassandraContainerExtensionIntegrationTest.class)
            .isRunning());
  }

  @Test
  void injectsTheSharedContainerAndAClassKeyspaceThatHoldsData(
      CassandraTestContainer cassandra, CassandraKeyspace keyspace) {
    assertSame(cassandra, CassandraContainerExtension.containerFor(getClass()));
    assertEquals(keyspace, CassandraContainerExtension.keyspaceFor(getClass()));
    assertEquals(keyspaceSeenBeforeAll, keyspace);
    // shared = true means the JVM-wide fixture for the annotation's (default) settings.
    assertSame(
        CassandraTestContainers.shared(CassandraContainerConfiguration.defaults()), cassandra);
    assertTrue(cassandra.isShared());
    // The prefix comes from the class's simple name, truncated to fit Cassandra's 48 characters.
    assertTrue(keyspace.name().startsWith("cassandracontainerextensionintegrat_"), keyspace.name());
    assertEquals(48, keyspace.name().length());
    try (var session = session(cassandra)) {
      session.execute("CREATE TABLE " + keyspace.name() + ".probe (id text PRIMARY KEY)");
      session.execute("INSERT INTO " + keyspace.name() + ".probe (id) VALUES ('stored')");
      assertEquals(
          "stored",
          session.execute("SELECT id FROM " + keyspace.name() + ".probe").one().getString(0));
    }
  }

  @Nested
  class NestedClass {
    @Test
    void getsItsOwnKeyspaceOnTheSameSharedContainer(
        CassandraTestContainer cassandra, CassandraKeyspace keyspace) {
      // A nested class inherits the enclosing class's settings, but not its keyspace.
      assertSame(
          CassandraContainerExtension.containerFor(
              CassandraContainerExtensionIntegrationTest.class),
          cassandra);
      assertNotEquals(keyspaceSeenBeforeAll, keyspace);
      assertTrue(keyspace.name().startsWith("nestedclass_"), keyspace.name());
      assertEquals(keyspace, CassandraContainerExtension.keyspaceFor(NestedClass.class));
    }
  }

  static CqlSession session(CassandraTestContainer cassandra) {
    return CqlSession.builder()
        .addContactPoint(cassandra.contactPoint())
        .withLocalDatacenter(cassandra.localDatacenter())
        .build();
  }
}
