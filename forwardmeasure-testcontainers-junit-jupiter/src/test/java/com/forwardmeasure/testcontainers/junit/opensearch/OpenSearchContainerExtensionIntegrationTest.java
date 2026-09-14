package com.forwardmeasure.testcontainers.junit.opensearch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WithOpenSearchContainer(securityEnabled = false)
class OpenSearchContainerExtensionIntegrationTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(OpenSearchContainerExtensionIntegrationTest.class);

  @Test
  void startsRealOpenSearchAndInjectsTheOwnedContainer(OpenSearchTestContainer container)
      throws Exception {
    LOGGER.info(
        "@WithOpenSearchContainer injected {} at {}",
        container.containerName(),
        container.hostEndpoint());

    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(container.hostEndpoint().resolve("/_cluster/health"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    LOGGER.info("GET /_cluster/health -> {} {}", response.statusCode(), response.body());

    assertTrue(container.isRunning());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300);
    assertFalse(container.isSecurityEnabled());
  }
}
