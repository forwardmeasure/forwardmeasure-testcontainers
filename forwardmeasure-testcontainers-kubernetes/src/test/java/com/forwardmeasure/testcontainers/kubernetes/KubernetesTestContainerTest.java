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
