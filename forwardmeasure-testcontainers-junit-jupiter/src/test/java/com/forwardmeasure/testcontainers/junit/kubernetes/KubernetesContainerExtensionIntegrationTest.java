package com.forwardmeasure.testcontainers.junit.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.testcontainers.kubernetes.KubernetesTestContainer;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WithKubernetesContainer
class KubernetesContainerExtensionIntegrationTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(KubernetesContainerExtensionIntegrationTest.class);

  @Test
  void startsARealK3sClusterAndInjectsTheOwnedContainer(KubernetesTestContainer container) {
    LOGGER.info("@WithKubernetesContainer injected a container, running={}", container.isRunning());

    assertTrue(container.isRunning());
    try (KubernetesClient client = container.createClient()) {
      var nodes = client.nodes().list().getItems();
      LOGGER.info("real cluster reports {} node(s)", nodes.size());
      assertFalse(nodes.isEmpty());
    }
  }
}
