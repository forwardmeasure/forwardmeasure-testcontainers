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
package com.forwardmeasure.testcontainers.quarkus.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.testcontainers.opensearch.OpenSearchContainerConfiguration;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainers;
import io.quarkus.test.common.DevServicesContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

/** Drives the Quarkus lifecycle manager directly against real OpenSearch containers. */
class OpenSearchTestResourceLifecycleManagerIntegrationTest {

  @WithOpenSearchTestContainer
  static class DefaultProperties {}

  @WithOpenSearchTestContainer(
      securityEnabled = true,
      shared = true,
      hostsProperty = "itineris.index.hosts",
      protocolProperty = "itineris.index.scheme",
      usernameProperty = "itineris.index.user",
      passwordProperty = "itineris.index.secret")
  static class SharedSecured {}

  @WithOpenSearchTestContainer(useNetworkEndpoint = true, networkAlias = "adapter-search")
  static class NetworkEndpoint {}

  @WithOpenSearchTestContainer(hostsProperty = "")
  static class NoHostsProperty {}

  @Test
  void defaultPropertiesReachAnOwnedContainerThatStopCloses() throws Exception {
    var manager = new OpenSearchTestResourceLifecycleManager();
    manager.init(DefaultProperties.class.getAnnotation(WithOpenSearchTestContainer.class));
    Map<String, String> properties = manager.start();
    URI health;
    try (var client = HttpClient.newHttpClient()) {
      try {
        // Exactly the configured keys: no credentials without security, nothing extra.
        assertEquals(
            Set.of("quarkus.opensearch.hosts", "quarkus.opensearch.protocol"), properties.keySet());
        assertEquals("http", properties.get("quarkus.opensearch.protocol"));
        health =
            URI.create(
                properties.get("quarkus.opensearch.protocol")
                    + "://"
                    + properties.get("quarkus.opensearch.hosts")
                    + "/_cluster/health");
        var response =
            client.send(
                HttpRequest.newBuilder(health).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertThrows(IllegalStateException.class, manager::start);
      } finally {
        manager.stop();
        manager.stop();
      }
      // The owned container is gone once the resource stops.
      var stopped = health;
      assertThrows(
          IOException.class,
          () ->
              client.send(
                  HttpRequest.newBuilder(stopped).timeout(Duration.ofSeconds(10)).GET().build(),
                  HttpResponse.BodyHandlers.discarding()));
    }
  }

  @Test
  void securedSharedPropertiesCarryHttpsAndWorkingCredentials() throws Exception {
    var annotation = SharedSecured.class.getAnnotation(WithOpenSearchTestContainer.class);
    var first = new OpenSearchTestResourceLifecycleManager();
    var second = new OpenSearchTestResourceLifecycleManager();
    first.init(annotation);
    second.init(annotation);
    try {
      Map<String, String> properties = first.start();
      assertEquals(
          Set.of(
              "itineris.index.hosts",
              "itineris.index.scheme",
              "itineris.index.user",
              "itineris.index.secret"),
          properties.keySet());
      assertEquals("https", properties.get("itineris.index.scheme"));
      assertEquals(properties, second.start());

      // The resource used the JVM-wide shared fixture for these settings; its CA verifies TLS.
      var shared =
          OpenSearchTestContainers.shared(
              new OpenSearchContainerConfiguration(
                  DockerImageName.parse(annotation.image()),
                  true,
                  Optional.empty(),
                  List.of(),
                  annotation.memoryBytes(),
                  annotation.memorySwapBytes()));
      assertEquals(
          shared.hostEndpoint().getHost() + ":" + shared.hostEndpoint().getPort(),
          properties.get("itineris.index.hosts"));
      var health =
          URI.create(
              properties.get("itineris.index.scheme")
                  + "://"
                  + properties.get("itineris.index.hosts")
                  + "/_cluster/health");
      String basic =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (properties.get("itineris.index.user")
                              + ":"
                              + properties.get("itineris.index.secret"))
                          .getBytes(StandardCharsets.UTF_8));
      try (var client = HttpClient.newBuilder().sslContext(trusting(shared)).build()) {
        assertEquals(401, status(client, HttpRequest.newBuilder(health).GET().build()));
        assertEquals(
            200,
            status(
                client,
                HttpRequest.newBuilder(health).header("Authorization", basic).GET().build()));
        // Stopping one resource leaves the shared container serving the other.
        first.stop();
        assertEquals(
            200,
            status(
                client,
                HttpRequest.newBuilder(health).header("Authorization", basic).GET().build()));
        second.stop();
        assertTrue(shared.isRunning());
      }
    } finally {
      first.stop();
      second.stop();
    }
  }

  @Test
  void incompleteConfigurationFailsBeforeStartingAContainer() {
    var manager = new OpenSearchTestResourceLifecycleManager();
    manager.stop();
    assertThrows(IllegalStateException.class, manager::start);
    manager.init(NetworkEndpoint.class.getAnnotation(WithOpenSearchTestContainer.class));
    assertThrows(IllegalStateException.class, manager::start);
    manager.init(NoHostsProperty.class.getAnnotation(WithOpenSearchTestContainer.class));
    assertThrows(IllegalStateException.class, manager::start);
    manager.stop();
  }

  @Test
  void devServicesNetworkEndpointIsUsableAndTheNetworkRemainsCallerOwned() throws Exception {
    try (var network = Network.newNetwork()) {
      var manager = new OpenSearchTestResourceLifecycleManager();
      manager.init(NetworkEndpoint.class.getAnnotation(WithOpenSearchTestContainer.class));
      manager.setIntegrationTestContext(
          new DevServicesContext() {
            @Override
            public Map<String, String> devServicesProperties() {
              return Map.of();
            }

            @Override
            public Optional<String> containerNetworkId() {
              return Optional.of(network.getId());
            }
          });
      try {
        var properties = manager.start();
        assertEquals("adapter-search:9200", properties.get("quarkus.opensearch.hosts"));
        assertEquals("http", properties.get("quarkus.opensearch.protocol"));
        try (var sibling =
            new GenericContainer<>("alpine:3.17")
                .withNetwork(network)
                .withCommand("sleep", "infinity")) {
          sibling.start();
          var result =
              sibling.execInContainer(
                  "wget",
                  "-qO-",
                  properties.get("quarkus.opensearch.protocol")
                      + "://"
                      + properties.get("quarkus.opensearch.hosts")
                      + "/_cluster/health");
          assertEquals(0, result.getExitCode(), result.getStderr());
          assertTrue(result.getStdout().contains("cluster_name"), result.getStdout());
        }
      } finally {
        manager.stop();
      }
      assertEquals(
          network.getId(),
          DockerClientFactory.instance()
              .client()
              .inspectNetworkCmd()
              .withNetworkId(network.getId())
              .exec()
              .getId());
    }
  }

  private static int status(HttpClient client, HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static SSLContext trusting(
      com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainer search)
      throws Exception {
    var store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    int index = 0;
    for (var certificate :
        CertificateFactory.getInstance("X.509")
            .generateCertificates(
                new ByteArrayInputStream(
                    search.certificateAuthorityPem().getBytes(StandardCharsets.US_ASCII)))) {
      store.setCertificateEntry("ca-" + index++, certificate);
    }
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var tls = SSLContext.getInstance("TLS");
    tls.init(null, trust.getTrustManagers(), null);
    return tls;
  }
}
