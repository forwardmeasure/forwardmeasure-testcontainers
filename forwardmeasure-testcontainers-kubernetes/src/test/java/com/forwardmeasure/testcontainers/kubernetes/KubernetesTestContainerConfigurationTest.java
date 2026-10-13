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
package com.forwardmeasure.testcontainers.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * Configuration the fixture accepts only before startup, and what it refuses - checked without
 * starting a cluster. The started-cluster refusals are in {@link KubernetesTestContainerTest}.
 */
class KubernetesTestContainerConfigurationTest {

  @Test
  void aNetworkNeedsANetworkAndAnAlias() {
    try (var network = Network.newNetwork();
        var kubernetes = new KubernetesTestContainer()) {
      assertThrows(NullPointerException.class, () -> kubernetes.withNetwork(null, "k3s"));
      assertThrows(IllegalArgumentException.class, () -> kubernetes.withNetwork(network, null));
      assertThrows(IllegalArgumentException.class, () -> kubernetes.withNetwork(network, " "));
      assertSame(kubernetes, kubernetes.withNetwork(network, "k3s"));
    }
  }

  @Test
  void aLogConsumerIsRequired() {
    try (var kubernetes = new KubernetesTestContainer()) {
      assertThrows(NullPointerException.class, () -> kubernetes.withLogConsumer(null));
      assertSame(kubernetes, kubernetes.withLogConsumer(frame -> {}));
    }
  }

  @Test
  void onlyARegularFileCanBeMountedReadOnly(@TempDir Path directory) throws Exception {
    Path fixture = Files.writeString(directory.resolve("fixture.yaml"), "kind: Fixture\n");
    try (var kubernetes = new KubernetesTestContainer()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> kubernetes.withReadOnlyHostFile(directory.resolve("missing.yaml"), "/fixture"));
      assertThrows(
          IllegalArgumentException.class,
          () -> kubernetes.withReadOnlyHostFile(directory, "/fixture"));
      assertSame(kubernetes, kubernetes.withReadOnlyHostFile(fixture, "/fixture.yaml"));
    }
  }

  @Test
  void coverageNeedsARealAgentAnOutputAndValidImagePrefixes(@TempDir Path directory)
      throws Exception {
    Path agent = Files.writeString(directory.resolve("jacocoagent.jar"), "not checked here");
    String previousAgent = System.getProperty(KubernetesTestContainer.JACOCO_AGENT_PROPERTY);
    String previousOutput = System.getProperty(KubernetesTestContainer.JACOCO_OUTPUT_PROPERTY);
    try (var kubernetes = new KubernetesTestContainer()) {
      System.setProperty(
          KubernetesTestContainer.JACOCO_AGENT_PROPERTY,
          directory.resolve("missing.jar").toString());
      assertThrows(IllegalArgumentException.class, () -> kubernetes.withJacocoCoverage("app:"));

      System.setProperty(KubernetesTestContainer.JACOCO_AGENT_PROPERTY, agent.toString());
      System.setProperty(KubernetesTestContainer.JACOCO_OUTPUT_PROPERTY, " ");
      assertThrows(IllegalArgumentException.class, () -> kubernetes.withJacocoCoverage("app:"));

      System.setProperty(
          KubernetesTestContainer.JACOCO_OUTPUT_PROPERTY,
          directory.resolve("jacoco.exec").toString());
      assertThrows(IllegalArgumentException.class, kubernetes::withJacocoCoverage);
      for (String prefix : new String[] {null, " ", "app'"})
        assertThrows(
            IllegalArgumentException.class,
            () -> kubernetes.withJacocoCoverage("ok:", prefix),
            String.valueOf(prefix));
      assertSame(kubernetes, kubernetes.withJacocoCoverage("app:", "worker:"));
    } finally {
      restore(KubernetesTestContainer.JACOCO_AGENT_PROPERTY, previousAgent);
      restore(KubernetesTestContainer.JACOCO_OUTPUT_PROPERTY, previousOutput);
    }
  }

  @Test
  void aClosedFixtureRefusesConfigurationAndStartup(@TempDir Path directory) throws Exception {
    Path fixture = Files.writeString(directory.resolve("fixture.yaml"), "kind: Fixture\n");
    var kubernetes = new KubernetesTestContainer();
    kubernetes.close();
    try (var network = Network.newNetwork()) {
      refused(() -> kubernetes.withNetwork(network, "k3s"));
    }
    refused(() -> kubernetes.withLogConsumer(frame -> {}));
    refused(() -> kubernetes.withReadOnlyHostFile(fixture, "/fixture.yaml"));
    refused(() -> kubernetes.withJacocoCoverage("app:"));
    assertEquals(
        "Kubernetes test container has been closed",
        assertThrows(IllegalStateException.class, kubernetes::start).getMessage());
  }

  @Test
  void aFailedStartClosesTheFixtureAndRethrows() {
    // A network Docker has never heard of: attaching the node to it fails the start.
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
    var kubernetes = new KubernetesTestContainer().withNetwork(absent, "k3s-gone");
    assertThrows(RuntimeException.class, kubernetes::start);
    assertEquals(
        "Kubernetes test container has been closed",
        assertThrows(IllegalStateException.class, kubernetes::start).getMessage());
    kubernetes.close();
  }

  private static void refused(Supplier<KubernetesTestContainer> configuration) {
    assertThrows(IllegalStateException.class, configuration::get);
  }

  private static void restore(String property, String value) {
    if (value == null) System.clearProperty(property);
    else System.setProperty(property, value);
  }
}
