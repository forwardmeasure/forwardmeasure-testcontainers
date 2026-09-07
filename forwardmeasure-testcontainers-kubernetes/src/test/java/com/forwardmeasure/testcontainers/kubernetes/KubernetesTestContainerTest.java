package com.forwardmeasure.testcontainers.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KubernetesTestContainerTest {

  @Test
  void startsARealClusterAndCreatesAWorkingClient() {
    try (var kubernetes = new KubernetesTestContainer().start()) {
      assertTrue(kubernetes.isRunning());
      assertFalse(kubernetes.kubeConfigYaml().isBlank());

      try (var client = kubernetes.createClient()) {
        var namespaces = client.namespaces().list().getItems();
        assertTrue(
            namespaces.stream().anyMatch(ns -> "default".equals(ns.getMetadata().getName())));
      }
    }
  }

  @Test
  void refusesEndpointsBeforeStartup() {
    try (var kubernetes = new KubernetesTestContainer()) {
      assertThrows(IllegalStateException.class, kubernetes::kubeConfigYaml);
    }
  }
}
