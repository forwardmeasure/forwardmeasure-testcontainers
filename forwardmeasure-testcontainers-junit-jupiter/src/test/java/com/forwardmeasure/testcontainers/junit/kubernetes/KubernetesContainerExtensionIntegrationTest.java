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
