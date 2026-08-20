package com.forwardmeasure.testcontainers.keycloak;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

class KeycloakTestContainerIntegrationTest {
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
      String user = keycloak.passwordToken("browser-test", "operator", "operator-password");
      String workload = keycloak.clientCredentialsToken("workload-test", "workload-test-secret");

      assertTrue(user.split("\\.").length >= 2);
      assertTrue(workload.split("\\.").length >= 2);
      assertNotEquals(user, workload);
    }
  }
}
