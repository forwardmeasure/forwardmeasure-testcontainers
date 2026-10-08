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
import org.junit.jupiter.api.Test;

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
  void closingAnUnstartedFixtureDoesNotStartInfrastructure() {
    var cassandra = new CassandraTestContainer();
    cassandra.close();
    cassandra.close();
    assertThrows(IllegalStateException.class, cassandra::start);
    assertThrows(IllegalStateException.class, cassandra::localDatacenter);
  }
}
