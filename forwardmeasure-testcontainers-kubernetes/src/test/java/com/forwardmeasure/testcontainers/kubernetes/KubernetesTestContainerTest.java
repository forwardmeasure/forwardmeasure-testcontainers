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
