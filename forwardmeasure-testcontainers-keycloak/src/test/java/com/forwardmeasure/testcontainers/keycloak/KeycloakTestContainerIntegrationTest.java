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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
      String alternate =
          keycloak.passwordTokenWithAlternateIssuer(
              "browser-test", "operator", "operator-password");
      String normalClaims = decodedSegment(user, 1);
      String alternateClaims = decodedSegment(alternate, 1);
      String expectedIssuer = "\"iss\":\"" + keycloak.issuer() + "\"";
      assertTrue(normalClaims.contains(expectedIssuer));
      assertFalse(
          alternateClaims.contains(expectedIssuer), "Issuer must change, not the signing key");
      assertEquals(decodedSegment(user, 0), decodedSegment(alternate, 0));
    }
  }

  @Test
  void networkTokensUseTheIssuerDiscoverableBySiblingServices() throws Exception {
    String realm;
    try (var stream = getClass().getResourceAsStream("/keycloak-test-realm.json")) {
      realm =
          new String(
              java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
    try (var network = org.testcontainers.containers.Network.newNetwork();
        var keycloak =
            new KeycloakTestContainer("forwardmeasure-test", realm, network, "identity").start()) {
      String token = keycloak.clientCredentialsToken("workload-test", "workload-test-secret");
      String claims =
          new String(
              java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
              StandardCharsets.UTF_8);
      assertTrue(claims.contains("\"iss\":\"" + keycloak.networkIssuer() + "\""));
      assertThrows(
          IllegalStateException.class,
          () ->
              keycloak.passwordTokenWithAlternateIssuer(
                  "browser-test", "operator", "operator-password"));
      try (var client =
          new org.testcontainers.containers.GenericContainer<>("alpine:3.17")
              .withNetwork(network)
              .withCommand("sleep", "infinity")) {
        client.start();
        var result = client.execInContainer("wget", "-qO-", keycloak.networkIssuer().toString());
        assertEquals(0, result.getExitCode(), result.getStderr());
        assertTrue(result.getStdout().contains("forwardmeasure-test"));
      }
      var denied =
          assertThrows(
              IllegalStateException.class,
              () ->
                  keycloak.passwordToken(
                      "browser-test", "operator", "wrong&password=operator-password"));
      assertTrue(denied.getMessage().contains("HTTP 400"), denied.getMessage());
      assertTrue(denied.getMessage().contains("invalid_grant"), denied.getMessage());
    }
  }

  @Test
  void incompleteNetworkConfigurationAndUnavailableEndpointsFailEarly() {
    try (var network = org.testcontainers.containers.Network.newNetwork()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new KeycloakTestContainer("realm", "{}", network, null));
      assertThrows(
          IllegalArgumentException.class,
          () -> new KeycloakTestContainer("realm", "{}", null, "alias"));
      assertThrows(
          IllegalArgumentException.class,
          () -> new KeycloakTestContainer("realm", "{}", network, " "));
    }
    assertThrows(IllegalArgumentException.class, () -> new KeycloakTestContainer(" ", "{}"));
    assertThrows(IllegalArgumentException.class, () -> new KeycloakTestContainer("realm", " "));
    try (var keycloak = new KeycloakTestContainer("realm", "{}")) {
      assertThrows(IllegalStateException.class, keycloak::issuer);
      assertThrows(IllegalStateException.class, keycloak::networkIssuer);
      assertThrows(
          IllegalArgumentException.class, () -> keycloak.clientCredentialsToken(" ", "secret"));
    }
  }

  private static String decodedSegment(String token, int segment) {
    return new String(
        java.util.Base64.getUrlDecoder().decode(token.split("\\.")[segment]),
        StandardCharsets.UTF_8);
  }
}
