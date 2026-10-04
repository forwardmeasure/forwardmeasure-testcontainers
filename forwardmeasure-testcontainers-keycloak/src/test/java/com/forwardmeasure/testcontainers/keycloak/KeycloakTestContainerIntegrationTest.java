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
package com.forwardmeasure.testcontainers.keycloak;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

class KeycloakTestContainerIntegrationTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(KeycloakTestContainerIntegrationTest.class);

  @Test
  void importsRealmAndIssuesUserAndWorkloadTokens() throws IOException {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable());
    String realm;
    try (var stream = getClass().getResourceAsStream("/keycloak-test-realm.json")) {
      if (stream == null) {
        throw new IllegalStateException("Missing Keycloak test realm");
      }
      realm = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    try (var keycloak = new KeycloakTestContainer("forwardmeasure-test", realm).start()) {
      LOGGER.info("Started Keycloak with realm 'forwardmeasure-test' imported");

      String user = keycloak.passwordToken("browser-test", "operator", "operator-password");
      String workload = keycloak.clientCredentialsToken("workload-test", "workload-test-secret");
      // Logging token segment counts only, never the tokens themselves, even for ephemeral
      // throwaway test containers.
      LOGGER.info(
          "Issued user token ({} segments) and workload token ({} segments)",
          user.split("\\.").length,
          workload.split("\\.").length);

      assertTrue(user.split("\\.").length >= 2);
      assertTrue(workload.split("\\.").length >= 2);
      assertNotEquals(user, workload);
    }
  }
}
