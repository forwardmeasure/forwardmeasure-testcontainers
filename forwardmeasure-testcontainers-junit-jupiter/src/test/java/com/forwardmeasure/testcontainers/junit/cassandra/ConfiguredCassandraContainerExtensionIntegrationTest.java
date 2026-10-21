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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.forwardmeasure.testcontainers.cassandra.CassandraContainerConfiguration;
import com.forwardmeasure.testcontainers.cassandra.CassandraKeyspace;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

/** Every annotation attribute reaches the container the extension starts. */
@WithCassandraContainer(
    image = "cassandra:5.0.8",
    maxHeapSize = "384M",
    heapNewSize = "64M",
    memoryBytes = 1610612736L,
    memorySwapBytes = 2147483648L,
    passwordAuthentication = true,
    configurationFile = "cassandra/byte-ordered-partitioner.yaml",
    startupTimeoutSeconds = 240,
    keyspacePerClass = true)
class ConfiguredCassandraContainerExtensionIntegrationTest {

  @Test
  void annotationSettingsConfigureAnOwnedAuthenticatedContainer(
      CassandraTestContainer cassandra, CassandraKeyspace keyspace) {
    // Catches an attribute the extension forgets to map: the record must match exactly.
    assertEquals(
        new CassandraContainerConfiguration(
            DockerImageName.parse("cassandra:5.0.8"),
            "384M",
            "64M",
            1610612736L,
            2147483648L,
            true,
            Optional.of("cassandra/byte-ordered-partitioner.yaml"),
            Duration.ofSeconds(240),
            Optional.empty(),
            List.of()),
        cassandra.configuration());
    assertFalse(cassandra.isShared());
    assertThrows(
        AllNodesFailedException.class,
        () ->
            CqlSession.builder()
                .addContactPoint(cassandra.contactPoint())
                .withLocalDatacenter(cassandra.localDatacenter())
                .build()
                .close());
    try (var admin =
        CqlSession.builder()
            .addContactPoint(cassandra.contactPoint())
            .withLocalDatacenter(cassandra.localDatacenter())
            .withAuthCredentials(cassandra.superuserName(), cassandra.superuserPassword())
            .build()) {
      var local = admin.execute("SELECT release_version, partitioner FROM system.local").one();
      assertEquals("5.0.8", local.getString("release_version"));
      assertEquals(
          "org.apache.cassandra.dht.ByteOrderedPartitioner", local.getString("partitioner"));
      // The class keyspace was created through authentication too.
      assertEquals(
          keyspace.name(),
          admin
              .execute(
                  "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?",
                  keyspace.name())
              .one()
              .getString(0));
    }
  }
}
