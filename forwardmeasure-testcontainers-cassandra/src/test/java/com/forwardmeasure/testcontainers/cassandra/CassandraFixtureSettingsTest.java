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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ExecInContainerPattern;
import org.testcontainers.utility.DockerImageName;

/** Proves each configuration setting reaches the real, running Cassandra node. */
class CassandraFixtureSettingsTest {

  private static final String BYTE_ORDERED_YAML = "cassandra/byte-ordered-partitioner.yaml";

  private static final String BYTE_ORDERED = "org.apache.cassandra.dht.ByteOrderedPartitioner";

  private static final Pattern NODETOOL_MAX_HEAP =
      Pattern.compile("Heap Memory \\(MB\\)\\s*:\\s*[0-9.]+\\s*/\\s*([0-9.]+)");

  @Test
  void customImageHeapAndMemoryLimitsReachTheRunningNode() throws Exception {
    // Catches settings that are recorded but never applied: Testcontainers' own CassandraContainer
    // defaults to a 1024M heap and the cassandra:5.0.5 image, either of which would show here.
    var configuration =
        CassandraContainerConfiguration.defaults()
            .withImage(DockerImageName.parse("cassandra:5.0.8"))
            .withHeap("384M", "64M")
            .withMemory(1536L * 1024 * 1024, 2048L * 1024 * 1024);
    try (var cassandra = new CassandraTestContainer(configuration).start();
        var session = session(cassandra)) {
      assertEquals(configuration, cassandra.configuration());
      assertEquals(
          "5.0.8", session.execute("SELECT release_version FROM system.local").one().getString(0));

      var docker = DockerClientFactory.instance().client();
      var inspected = docker.inspectContainerCmd(cassandra.containerName()).exec();
      assertEquals(1536L * 1024 * 1024, inspected.getHostConfig().getMemory());
      assertEquals(2048L * 1024 * 1024, inspected.getHostConfig().getMemorySwap());
      assertTrue(
          java.util.List.of(inspected.getConfig().getEnv()).contains("HEAP_NEWSIZE=64M"),
          "HEAP_NEWSIZE must reach the container's environment");

      var nodetool = ExecInContainerPattern.execInContainer(docker, inspected, "nodetool", "info");
      assertEquals(0, nodetool.getExitCode(), nodetool.getStderr());
      var heap = NODETOOL_MAX_HEAP.matcher(nodetool.getStdout());
      assertTrue(heap.find(), nodetool.getStdout());
      // The JVM reports exactly -Xmx as its maximum heap.
      assertEquals(384.0, Double.parseDouble(heap.group(1)), 1.0, nodetool.getStdout());
    }
  }

  @Test
  void aSuppliedConfigurationFileReplacesTheImagesCassandraYaml() {
    // Catches a configuration file that is copied somewhere Cassandra never reads, or not at all.
    try (var cassandra =
            new CassandraTestContainer(
                    CassandraContainerConfiguration.defaults()
                        .withConfigurationFile(BYTE_ORDERED_YAML))
                .start();
        var session = session(cassandra)) {
      assertEquals(
          BYTE_ORDERED, session.execute("SELECT partitioner FROM system.local").one().getString(0));
      // The fixture's keyspace helpers still work on the caller's file.
      var keyspace = cassandra.createUniqueKeyspace("byte_ordered");
      session.execute("CREATE TABLE " + keyspace.name() + ".probe (id text PRIMARY KEY)");
      session.execute("INSERT INTO " + keyspace.name() + ".probe (id) VALUES ('kept')");
      assertEquals(
          "kept",
          session.execute("SELECT id FROM " + keyspace.name() + ".probe").one().getString(0));
    }
  }

  @Test
  void passwordAuthenticationIsAppliedToASuppliedConfigurationFile() {
    // Catches the authenticator edit running on the image's file and then being overwritten by the
    // caller's (or vice versa): both the caller's partitioner and authentication must be in force.
    try (var cassandra =
        new CassandraTestContainer(
                CassandraContainerConfiguration.defaults()
                    .withConfigurationFile("/" + BYTE_ORDERED_YAML)
                    .withPasswordAuthentication(true))
            .start()) {
      assertThrows(AllNodesFailedException.class, () -> session(cassandra).close());
      try (var admin =
          CqlSession.builder()
              .addContactPoint(cassandra.contactPoint())
              .withLocalDatacenter(cassandra.localDatacenter())
              .withAuthCredentials(cassandra.superuserName(), cassandra.superuserPassword())
              .build()) {
        assertEquals(
            BYTE_ORDERED, admin.execute("SELECT partitioner FROM system.local").one().getString(0));
        // The helpers authenticate as the superuser themselves.
        var keyspace = cassandra.createUniqueKeyspace("authenticated");
        assertEquals(
            1,
            admin
                .execute(
                    "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?",
                    keyspace.name())
                .all()
                .size());
        cassandra.dropKeyspace(keyspace.name());
        assertEquals(
            0,
            admin
                .execute(
                    "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?",
                    keyspace.name())
                .all()
                .size());
      }
    }
  }

  private static CqlSession session(CassandraTestContainer cassandra) {
    return CqlSession.builder()
        .addContactPoint(cassandra.contactPoint())
        .withLocalDatacenter(cassandra.localDatacenter())
        .build();
  }
}
