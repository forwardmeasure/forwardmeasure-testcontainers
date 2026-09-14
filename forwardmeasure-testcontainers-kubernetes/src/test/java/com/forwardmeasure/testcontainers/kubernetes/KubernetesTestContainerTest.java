package com.forwardmeasure.testcontainers.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
