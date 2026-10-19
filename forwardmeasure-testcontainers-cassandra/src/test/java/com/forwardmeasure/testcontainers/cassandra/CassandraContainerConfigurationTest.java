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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

/** Configuration rules that must hold before any container exists. */
class CassandraContainerConfigurationTest {

  private static final CassandraContainerConfiguration DEFAULTS =
      CassandraContainerConfiguration.defaults();

  @Test
  void defaultsKeepTheFixturesLongStandingSettings() {
    // Catches a silent change of the image, heap or memory cap every existing caller relies on.
    assertEquals(DockerImageName.parse("cassandra:5.0.5"), DEFAULTS.image());
    assertEquals("512M", DEFAULTS.maxHeapSize());
    assertEquals("100M", DEFAULTS.heapNewSize());
    assertEquals(2L * 1024 * 1024 * 1024, DEFAULTS.memoryBytes());
    assertEquals(0L, DEFAULTS.memorySwapBytes());
    assertFalse(DEFAULTS.passwordAuthentication());
    assertEquals(Optional.empty(), DEFAULTS.configurationFile());
    assertEquals(Duration.ofMinutes(3), DEFAULTS.startupTimeout());
    assertEquals(Optional.empty(), DEFAULTS.networkId());
    assertEquals(List.of(), DEFAULTS.networkAliases());
  }

  @Test
  void copyMethodsChangeOnlyTheirOwnSetting() {
    var image = DockerImageName.parse("cassandra:5.0.8");
    assertEquals(image, DEFAULTS.withImage(image).image());
    var heap = DEFAULTS.withHeap("1G", "200M");
    assertEquals("1G", heap.maxHeapSize());
    assertEquals("200M", heap.heapNewSize());
    var memory = DEFAULTS.withMemory(3L << 30, 4L << 30);
    assertEquals(3L << 30, memory.memoryBytes());
    assertEquals(4L << 30, memory.memorySwapBytes());
    assertTrue(DEFAULTS.withPasswordAuthentication(true).passwordAuthentication());
    assertEquals(
        Optional.of("cassandra/byte-ordered-partitioner.yaml"),
        DEFAULTS
            .withConfigurationFile("cassandra/byte-ordered-partitioner.yaml")
            .configurationFile());
    assertEquals(
        Duration.ofSeconds(30),
        DEFAULTS.withStartupTimeout(Duration.ofSeconds(30)).startupTimeout());
    var networked = DEFAULTS.withNetwork("network", List.of("cassandra"));
    assertEquals(Optional.of("network"), networked.networkId());
    assertEquals(List.of("cassandra"), networked.networkAliases());
    // Every other setting is carried over untouched, and the original is never mutated.
    assertEquals(
        new CassandraContainerConfiguration(
            DEFAULTS.image(),
            DEFAULTS.maxHeapSize(),
            DEFAULTS.heapNewSize(),
            DEFAULTS.memoryBytes(),
            DEFAULTS.memorySwapBytes(),
            DEFAULTS.passwordAuthentication(),
            DEFAULTS.configurationFile(),
            DEFAULTS.startupTimeout(),
            Optional.of("network"),
            List.of("cassandra")),
        networked);
    assertEquals(DEFAULTS.withHeap("1G", "200M").withHeap("512M", "100M"), DEFAULTS);
    assertEquals(CassandraContainerConfiguration.defaults(), DEFAULTS);
    assertNotEquals(DEFAULTS, DEFAULTS.withPasswordAuthentication(true));
  }

  @Test
  void invalidHeapSettingsAreRejected() {
    // Each would make cassandra-env.sh refuse to start, or start a JVM the cgroup then kills.
    for (String[] heap :
        new String[][] {
          {"512", "100M"}, // no unit
          {"512MB", "100M"}, // not a JVM size
          {"0M", "100M"}, // zero
          {"-1G", "100M"}, // negative
          {"512M", " "}, // blank
          {"512M", "512M"}, // new generation as big as the heap
          {"256M", "1G"}, // new generation bigger than the heap
          {"4G", "100M"} // heap at or above the 2 GiB container cap
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> DEFAULTS.withHeap(heap[0], heap[1]),
          () -> String.join("/", heap));
    }
    assertThrows(NullPointerException.class, () -> DEFAULTS.withHeap(null, "100M"));
    // Unconstrained memory allows any heap; lower-case units are what the JVM accepts too.
    assertEquals("4g", DEFAULTS.withMemory(0, 0).withHeap("4g", "512m").maxHeapSize());
  }

  @Test
  void invalidMemoryTimeoutFileAndNetworkSettingsAreRejected() {
    for (long[] limits : new long[][] {{-1, 0}, {0, -1}, {4L << 30, 3L << 30}}) {
      assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withMemory(limits[0], limits[1]));
    }
    assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withStartupTimeout(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> DEFAULTS.withStartupTimeout(Duration.ofSeconds(-1)));
    assertThrows(NullPointerException.class, () -> DEFAULTS.withStartupTimeout(null));
    assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withConfigurationFile(" "));
    assertThrows(NullPointerException.class, () -> DEFAULTS.withConfigurationFile(null));
    assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withNetwork(" ", List.of("a")));
    assertThrows(
        IllegalArgumentException.class, () -> DEFAULTS.withNetwork("network", List.of(" ")));
    assertThrows(NullPointerException.class, () -> DEFAULTS.withImage(null));
  }

  @Test
  void networkAliasesAreCopiedAndTheStringFormIsReadable() {
    var aliases = new ArrayList<>(List.of("cassandra"));
    var configuration = DEFAULTS.withNetwork("network", aliases);
    aliases.clear();
    assertEquals(List.of("cassandra"), configuration.networkAliases());
    assertThrows(
        UnsupportedOperationException.class, () -> configuration.networkAliases().add("other"));
    String text = configuration.withPasswordAuthentication(true).toString();
    assertTrue(text.contains("passwordAuthentication=true"), text);
    assertTrue(text.contains("cassandra:5.0.5"), text);
    // The superuser's password is never part of the configuration's text.
    assertFalse(text.contains("password=") || text.contains("cassandra/cassandra"), text);
  }

  @Test
  void aMissingConfigurationFileFailsBeforeAnyContainerIsCreated() {
    var missing = DEFAULTS.withConfigurationFile("cassandra/does-not-exist.yaml");
    assertThrows(IllegalArgumentException.class, () -> new CassandraTestContainer(missing));
  }

  @Test
  void keyspacePrefixesAreNormalisedToValidUnquotedNames() {
    // Catches names Cassandra would reject (too long, upper case, punctuation, leading digit).
    assertEquals(
        "itineris_graph_test", CassandraTestContainer.normalisePrefix("Itineris-Graph Test"));
    assertEquals("k42abc", CassandraTestContainer.normalisePrefix("42abc"));
    assertEquals("k_x", CassandraTestContainer.normalisePrefix("_x"));
    assertEquals("k__", CassandraTestContainer.normalisePrefix("é!"));
    String longPrefix = CassandraTestContainer.normalisePrefix("A".repeat(100));
    assertEquals(CassandraKeyspace.MAX_NAME_LENGTH - 13, longPrefix.length());
    assertTrue(longPrefix.chars().allMatch(c -> c == 'a'));
    assertThrows(IllegalArgumentException.class, () -> CassandraTestContainer.normalisePrefix(" "));
    assertThrows(NullPointerException.class, () -> CassandraTestContainer.normalisePrefix(null));
  }

  @Test
  void keyspaceNamesMustBeValidUnquotedIdentifiers() {
    assertEquals("graph_1", new CassandraKeyspace("graph_1").name());
    assertEquals("a".repeat(48), new CassandraKeyspace("a".repeat(48)).name());
    for (String invalid :
        new String[] {"", "Graph", "1graph", "_graph", "a;drop", "a".repeat(49)}) {
      assertThrows(IllegalArgumentException.class, () -> new CassandraKeyspace(invalid), invalid);
    }
    assertThrows(NullPointerException.class, () -> new CassandraKeyspace(null));
  }
}
