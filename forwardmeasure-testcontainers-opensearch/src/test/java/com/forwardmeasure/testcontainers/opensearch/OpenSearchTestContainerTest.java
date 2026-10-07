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
package com.forwardmeasure.testcontainers.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class OpenSearchTestContainerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(OpenSearchTestContainerTest.class);

  @Test
  void startsRealOpenSearchWithSecurityDisabledByDefault() throws Exception {
    try (var openSearch = new OpenSearchTestContainer().start()) {
      LOGGER.info("Started {} at {}", openSearch.containerName(), openSearch.hostEndpoint());

      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(openSearch.hostEndpoint().resolve("/_cluster/health"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      LOGGER.info("GET /_cluster/health -> {} {}", response.statusCode(), response.body());

      assertTrue(openSearch.isRunning());
      assertTrue(response.statusCode() >= 200 && response.statusCode() < 300);
      assertFalse(openSearch.isSecurityEnabled());
      assertThrows(IllegalStateException.class, openSearch::networkEndpoint);
    }
  }

  @Test
  void refusesEndpointsBeforeStartup() {
    try (var openSearch = new OpenSearchTestContainer()) {
      LOGGER.info("Confirming hostEndpoint() rejects a not-yet-started container");
      assertThrows(IllegalStateException.class, openSearch::hostEndpoint);
    }
  }

  @Test
  void siblingCanReadIndexedDocumentsAndNetworkRemainsCallerOwned() throws Exception {
    try (var network = org.testcontainers.containers.Network.newNetwork()) {
      var d = OpenSearchContainerConfiguration.defaults();
      var configuration =
          new OpenSearchContainerConfiguration(
                  d.image(),
                  false,
                  java.util.Optional.empty(),
                  java.util.List.of(),
                  2L * 1024 * 1024 * 1024,
                  3L * 1024 * 1024 * 1024)
              .withNetwork(network.getId(), java.util.List.of("fixture-search"));
      var search = new OpenSearchTestContainer(configuration);
      try (search;
          var client = HttpClient.newHttpClient()) {
        search.start();
        var name = search.containerName();
        assertSame(search, search.start());
        assertEquals(name, search.containerName());
        assertSame(configuration, search.configuration());
        var write =
            client.send(
                HttpRequest.newBuilder(
                        search.hostEndpoint().resolve("/contract/_doc/one?refresh=true"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"fixture-contract\"}"))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, write.statusCode(), write.body());
        try (var sibling =
            new org.testcontainers.containers.GenericContainer<>("alpine:3.17")
                .withNetwork(network)
                .withCommand("sleep", "infinity")) {
          sibling.start();
          var result =
              sibling.execInContainer(
                  "wget",
                  "-qO-",
                  search.networkEndpoint().resolve("/contract/_doc/one").toString());
          assertEquals(0, result.getExitCode(), result.getStderr());
          assertTrue(result.getStdout().contains("fixture-contract"));
        }
      }
      search.close();
      assertFalse(search.isRunning());
      assertThrows(IllegalStateException.class, search::start);
      assertThrows(IllegalStateException.class, search::networkEndpoint);
      assertEquals(
          network.getId(),
          org.testcontainers.DockerClientFactory.instance()
              .client()
              .inspectNetworkCmd()
              .withNetworkId(network.getId())
              .exec()
              .getId());
    }
  }

  @Test
  void invalidNetworksAndLimitsAreRejectedAndConfigurationIsImmutable() {
    var d = OpenSearchContainerConfiguration.defaults();
    for (long[] limits : new long[][] {{-1, 0}, {0, -1}, {512, 256}}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new OpenSearchContainerConfiguration(
                  d.image(),
                  false,
                  java.util.Optional.empty(),
                  java.util.List.of(),
                  limits[0],
                  limits[1]));
    }
    assertThrows(
        IllegalArgumentException.class, () -> d.withNetwork(" ", java.util.List.of("alias")));
    assertThrows(
        IllegalArgumentException.class, () -> d.withNetwork("network", java.util.List.of(" ")));
    var aliases = new java.util.ArrayList<>(java.util.List.of("search"));
    var config = d.withNetwork("network", aliases);
    aliases.clear();
    assertEquals(java.util.List.of("search"), config.networkAliases());
    assertTrue(config.toString().contains("search"));
  }
}
