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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real TLS listeners: encrypted persistence, password auth, trust refusal and required client
 * identity.
 */
class CassandraTlsFixtureTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void encryptedCqlPersistsAndRejectsUntrustedOrUnencryptedClients(boolean mutualTls) {
    try (var certificates = CassandraTlsMaterial.create();
        var unrelated = CassandraTlsMaterial.create();
        var cassandra =
            new CassandraTestContainer(
                    CassandraContainerConfiguration.defaults()
                        .withPasswordAuthentication(true)
                        .withTls(certificates.configuration(mutualTls)))
                .start()) {
      var tls = certificates.configuration(mutualTls);
      var keyspace = cassandra.createUniqueKeyspace("encrypted");
      try {
        try (var session = connect(cassandra, tls, true)) {
          session.execute(
              "CREATE TABLE " + keyspace.name() + ".probe (id int PRIMARY KEY, value text)");
          session.execute(
              "INSERT INTO " + keyspace.name() + ".probe (id, value) VALUES (1, 'encrypted')");
        }
        try (var session = connect(cassandra, tls, true)) {
          assertEquals(
              "encrypted",
              session
                  .execute("SELECT value FROM " + keyspace.name() + ".probe WHERE id = 1")
                  .one()
                  .getString("value"));
        }
        assertThrows(
            AllNodesFailedException.class,
            () -> {
              try (var ignored = connect(cassandra, null, false)) {
                throw new AssertionError("A TLS-only Cassandra listener accepted plaintext CQL");
              }
            });
        var wrongTrust =
            new CassandraTlsConfiguration(
                tls.serverKeyStore(),
                tls.serverTrustStore(),
                unrelated.configuration(false).clientTrustStore(),
                tls.clientKeyStore(),
                mutualTls,
                true);
        assertThrows(
            AllNodesFailedException.class,
            () -> {
              try (var ignored = connect(cassandra, wrongTrust, true)) {
                throw new AssertionError("Cassandra's server certificate was not verified");
              }
            });
        if (mutualTls) {
          assertThrows(
              AllNodesFailedException.class,
              () -> {
                try (var ignored = connect(cassandra, tls, false)) {
                  throw new AssertionError(
                      "Cassandra accepted a client without the required certificate");
                }
              });
        }
        assertTrue(cassandra.isRunning());
      } finally {
        cassandra.dropKeyspace(keyspace.name());
      }
    }
  }

  private static CqlSession connect(
      CassandraTestContainer cassandra, CassandraTlsConfiguration tls, boolean identity) {
    var configuration =
        DriverConfigLoader.programmaticBuilder()
            .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofSeconds(3))
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(5));
    if (tls != null) {
      tls.configureClient(configuration, identity);
    }
    return CqlSession.builder()
        .addContactPoint(cassandra.contactPoint())
        .withLocalDatacenter(cassandra.localDatacenter())
        .withAuthCredentials(cassandra.superuserName(), cassandra.superuserPassword())
        .withConfigLoader(configuration.build())
        .build();
  }
}
