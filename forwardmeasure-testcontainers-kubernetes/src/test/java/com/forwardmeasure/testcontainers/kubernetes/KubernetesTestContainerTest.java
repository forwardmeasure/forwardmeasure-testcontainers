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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class KubernetesTestContainerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesTestContainerTest.class);

  @Test
  void startsARealClusterAndCreatesAWorkingClient() {
    try (var kubernetes = new KubernetesTestContainer().start()) {
      LOGGER.info("Started K3s cluster");
      assertTrue(kubernetes.isRunning());
      assertFalse(kubernetes.kubeConfigYaml().isBlank());
      // Configuration belongs before startup; a running cluster refuses it.
      try (var network = org.testcontainers.containers.Network.newNetwork()) {
        assertThrows(IllegalStateException.class, () -> kubernetes.withNetwork(network, "k3s"));
      }
      assertThrows(IllegalStateException.class, () -> kubernetes.withLogConsumer(frame -> {}));
      assertThrows(
          IllegalStateException.class,
          () -> kubernetes.withReadOnlyHostFile(java.nio.file.Path.of("/etc/hostname"), "/x"));
      assertThrows(IllegalStateException.class, () -> kubernetes.withJacocoCoverage("app:"));

      try (var client = kubernetes.createClient()) {
        var namespaces = client.namespaces().list().getItems();
        LOGGER.info(
            "Namespaces: {}", namespaces.stream().map(ns -> ns.getMetadata().getName()).toList());
        assertTrue(
            namespaces.stream().anyMatch(ns -> "default".equals(ns.getMetadata().getName())));
      }
    }
  }

  @Test
  void injectsTheAgentIntoMatchingJobsAndDeploymentsAndCollectsWhatTheyRecord(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    String javaImage = "eclipse-temurin:25-jre-ubi10-minimal";
    new org.testcontainers.images.RemoteDockerImage(
            org.testcontainers.utility.DockerImageName.parse(javaImage))
        .get();
    new org.testcontainers.images.RemoteDockerImage(
            org.testcontainers.utility.DockerImageName.parse("alpine:3.17"))
        .get();
    java.nio.file.Path output = directory.resolve("jacoco.exec");
    String agent = System.getProperty("forwardmeasure.testcontainers.kubernetes.it.jacoco-agent");
    String previousAgent = System.setProperty(KubernetesTestContainer.JACOCO_AGENT_PROPERTY, agent);
    String previousOutput =
        System.setProperty(KubernetesTestContainer.JACOCO_OUTPUT_PROPERTY, output.toString());
    try (var kubernetes =
        new KubernetesTestContainer().withJacocoCoverage("eclipse-temurin:").start()) {
      kubernetes.loadImage(javaImage);
      kubernetes.loadImage("alpine:3.17");
      try (var client = kubernetes.createClient()) {
        var job =
            new io.fabric8.kubernetes.api.model.batch.v1.JobBuilder()
                .withNewMetadata()
                .withName("coverage-job")
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withNewTemplate()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("java")
                .withImage(javaImage)
                .withImagePullPolicy("Never")
                .withCommand("java", "-version")
                .addNewEnv()
                .withName("JAVA_TOOL_OPTIONS")
                .withValue("-Xmx64m")
                .endEnv()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        client.batch().v1().jobs().inNamespace("default").resource(job).create();
        var server =
            new io.fabric8.kubernetes.api.model.apps.DeploymentBuilder()
                .withNewMetadata()
                .withName("coverage-server")
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .addToMatchLabels("app", "coverage-server")
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", "coverage-server")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("server")
                .withImage(javaImage)
                .withImagePullPolicy("Never")
                .withCommand("jwebserver", "-b", "0.0.0.0", "-p", "8000", "-d", "/tmp")
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        client.apps().deployments().inNamespace("default").resource(server).create();
        var bystander =
            new io.fabric8.kubernetes.api.model.PodBuilder()
                .withNewMetadata()
                .withName("coverage-bystander")
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("shell")
                .withImage("alpine:3.17")
                .withImagePullPolicy("Never")
                .withCommand("sh", "-c", "printf bystander")
                .endContainer()
                .endSpec()
                .build();
        client.pods().inNamespace("default").resource(bystander).create();

        client
            .batch()
            .v1()
            .jobs()
            .inNamespace("default")
            .withName("coverage-job")
            .waitUntilCondition(
                j -> j != null && j.getStatus() != null && j.getStatus().getSucceeded() != null,
                120,
                java.util.concurrent.TimeUnit.SECONDS);
        client
            .apps()
            .deployments()
            .inNamespace("default")
            .withName("coverage-server")
            .waitUntilReady(120, java.util.concurrent.TimeUnit.SECONDS);
        var serverPod =
            client.pods().inNamespace("default").withLabel("app", "coverage-server").list();
        String serverPodName = serverPod.getItems().get(0).getMetadata().getName();
        var serverEnv = serverPod.getItems().get(0).getSpec().getContainers().get(0).getEnv();
        assertTrue(
            serverEnv.stream()
                .anyMatch(
                    e ->
                        e.getName().equals("JAVA_TOOL_OPTIONS")
                            && e.getValue().startsWith("-javaagent:")),
            serverEnv.toString());
        String jobPodName =
            client
                .pods()
                .inNamespace("default")
                .withLabel("job-name", "coverage-job")
                .list()
                .getItems()
                .get(0)
                .getMetadata()
                .getName();
        var bystanderPod =
            client.pods().inNamespace("default").withName("coverage-bystander").get();
        assertFalse(
            bystanderPod.getMetadata().getLabels() != null
                && bystanderPod
                    .getMetadata()
                    .getLabels()
                    .containsKey(KubernetesTestContainer.JACOCO_POD_LABEL),
            "pods from other images are left alone");

        kubernetes.collectJacocoCoverage();

        byte[] data = java.nio.file.Files.readAllBytes(output);
        // JaCoCo execution data starts with its header block: 0x01, then magic 0xC0C0.
        assertEquals(0x01, data[0]);
        assertEquals((byte) 0xC0, data[1]);
        assertEquals((byte) 0xC0, data[2]);
        // Each JVM's session id is its hostname, i.e. its pod name: the Job's JVM wrote on exit
        // and the Deployment's JVM wrote from its shutdown hook when the workload was deleted.
        String recorded = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(recorded.contains(jobPodName), "Job JVM recorded nothing");
        assertTrue(recorded.contains(serverPodName), "Deployment JVM recorded nothing");
        assertTrue(
            client
                .pods()
                .inNamespace("default")
                .withLabel("app", "coverage-server")
                .list()
                .getItems()
                .isEmpty(),
            "collection stops the workloads it collects from");
      }
    } finally {
      restore(KubernetesTestContainer.JACOCO_AGENT_PROPERTY, previousAgent);
      restore(KubernetesTestContainer.JACOCO_OUTPUT_PROPERTY, previousOutput);
    }
  }

  @Test
  void aMatchingPodThatRecordsNothingFailsCollectionAndAClusterThatRanNoneHasNothingToCollect(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    new org.testcontainers.images.RemoteDockerImage(
            org.testcontainers.utility.DockerImageName.parse("alpine:3.17"))
        .get();
    java.nio.file.Path output = directory.resolve("jacoco.exec");
    String previousAgent =
        System.setProperty(
            KubernetesTestContainer.JACOCO_AGENT_PROPERTY,
            System.getProperty("forwardmeasure.testcontainers.kubernetes.it.jacoco-agent"));
    String previousOutput =
        System.setProperty(KubernetesTestContainer.JACOCO_OUTPUT_PROPERTY, output.toString());
    try {
      try (var idle = new KubernetesTestContainer().withJacocoCoverage("alpine:").start()) {
        idle.collectJacocoCoverage();
        assertFalse(java.nio.file.Files.exists(output), "nothing ran, nothing is written");
      }
      // An injected pod with no JVM in it: the agent never loads, so nothing is recorded.
      try (var silent = new KubernetesTestContainer().withJacocoCoverage("alpine:").start()) {
        silent.loadImage("alpine:3.17");
        try (var client = silent.createClient()) {
          var pod =
              new io.fabric8.kubernetes.api.model.PodBuilder()
                  .withNewMetadata()
                  .withName("no-jvm")
                  .endMetadata()
                  .withNewSpec()
                  .withRestartPolicy("Never")
                  .addNewContainer()
                  .withName("shell")
                  .withImage("alpine:3.17")
                  .withImagePullPolicy("Never")
                  .withCommand("sh", "-c", "printf no-jvm")
                  .endContainer()
                  .endSpec()
                  .build();
          client.pods().inNamespace("default").resource(pod).create();
          client
              .pods()
              .inNamespace("default")
              .withName("no-jvm")
              .waitUntilCondition(
                  p -> p != null && "Succeeded".equals(p.getStatus().getPhase()),
                  90,
                  java.util.concurrent.TimeUnit.SECONDS);
        }
        var failure = assertThrows(IllegalStateException.class, silent::collectJacocoCoverage);
        assertTrue(failure.getMessage().contains("no-jvm"), failure.getMessage());
      }
    } finally {
      restore(KubernetesTestContainer.JACOCO_AGENT_PROPERTY, previousAgent);
      restore(KubernetesTestContainer.JACOCO_OUTPUT_PROPERTY, previousOutput);
    }
  }

  @Test
  void coverageIsANoOpWithoutTheAgentProperty() {
    String previous = System.clearProperty(KubernetesTestContainer.JACOCO_AGENT_PROPERTY);
    try (var kubernetes = new KubernetesTestContainer()) {
      assertSame(kubernetes, kubernetes.withJacocoCoverage("eclipse-temurin:"));
    } finally {
      restore(KubernetesTestContainer.JACOCO_AGENT_PROPERTY, previous);
    }
  }

  private static void restore(String property, String value) {
    if (value == null) System.clearProperty(property);
    else System.setProperty(property, value);
  }

  @Test
  void refusesEndpointsBeforeStartup() {
    try (var kubernetes = new KubernetesTestContainer()) {
      LOGGER.info("Confirming kubeConfigYaml() rejects a not-yet-started container");
      assertThrows(IllegalStateException.class, kubernetes::kubeConfigYaml);
    }
  }

  @Test
  void importedImageRunsByDigestWithoutRegistryAccessAndClosureIsFinal() throws Exception {
    // Ensure the source image exists locally without relying on prior tests in the reactor.
    new org.testcontainers.images.RemoteDockerImage(
            org.testcontainers.utility.DockerImageName.parse("alpine:3.17"))
        .get();
    var kubernetes = new KubernetesTestContainer();
    try (kubernetes) {
      kubernetes.start();
      assertSame(kubernetes, kubernetes.start());
      assertEquals(
          KubernetesContainerConfiguration.DEFAULT_IMAGE, kubernetes.configuration().image());
      var missing =
          assertThrows(
              IllegalStateException.class,
              () ->
                  kubernetes.loadImage(
                      "forwardmeasure/missing-fixture:" + java.util.UUID.randomUUID()));
      assertTrue(missing.getMessage().contains("docker save exited"), missing.getMessage());
      String digest = kubernetes.loadImageAndPinDigest("alpine:3.17");
      assertTrue(digest.matches("docker.io/library/alpine@sha256:[0-9a-f]{64}"), digest);
      try (var client = kubernetes.createClient()) {
        var pod =
            new io.fabric8.kubernetes.api.model.PodBuilder()
                .withNewMetadata()
                .withName("digest-contract")
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("proof")
                .withImage(digest)
                .withImagePullPolicy("Never")
                .withCommand("sh", "-c", "printf digest-contract-ok")
                .endContainer()
                .endSpec()
                .build();
        client.pods().inNamespace("default").resource(pod).create();
        try {
          var completed =
              client
                  .pods()
                  .inNamespace("default")
                  .withName("digest-contract")
                  .waitUntilCondition(
                      p ->
                          p != null
                              && p.getStatus() != null
                              && ("Succeeded".equals(p.getStatus().getPhase())
                                  || "Failed".equals(p.getStatus().getPhase())),
                      90,
                      java.util.concurrent.TimeUnit.SECONDS);
          assertEquals("Succeeded", completed.getStatus().getPhase());
          assertEquals(
              "digest-contract-ok",
              client.pods().inNamespace("default").withName("digest-contract").getLog());
        } finally {
          client.pods().inNamespace("default").withName("digest-contract").delete();
        }
      }
    }
    kubernetes.close();
    assertFalse(kubernetes.isRunning());
    assertThrows(IllegalStateException.class, kubernetes::start);
    assertThrows(IllegalStateException.class, () -> kubernetes.loadImage("alpine:3.17"));
  }
}
