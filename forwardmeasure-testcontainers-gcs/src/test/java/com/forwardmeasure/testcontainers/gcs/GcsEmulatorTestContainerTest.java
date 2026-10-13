/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.forwardmeasure.testcontainers.gcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;

class GcsEmulatorTestContainerTest {
  private static final String CURL_IMAGE = "curlimages/curl:8.11.1";
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** What a peer container on {@code network} reads from {@code url}; fails unless curl exits 0. */
  private static String fromPeer(Network network, String url) {
    try (GenericContainer<?> peer =
        new GenericContainer<>(CURL_IMAGE)
            .withNetwork(network)
            .withCommand("-sSf", "--retry", "20", "--retry-connrefused", "--retry-delay", "1", url)
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
      peer.start();
      return peer.getLogs();
    }
  }

  @Test
  void hostAndNetworkClientsReachTheSameServer() throws Exception {
    try (Network network = Network.newNetwork();
        var gcs = new GcsEmulatorTestContainer(network, "gcs-fixture")) {
      assertSame(gcs, gcs.start());
      assertSame(gcs, gcs.start());
      assertEquals(URI.create("http://gcs-fixture:4443"), gcs.networkEndpoint());

      // A bucket created from the host...
      HttpResponse<String> created =
          HTTP.send(
              HttpRequest.newBuilder(gcs.hostEndpoint().resolve("/storage/v1/b?project=fixture"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"fixture-bucket\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, created.statusCode(), created.body());
      // ...is listed to a peer on the network, through the fixture's network endpoint.
      assertTrue(
          fromPeer(network, gcs.networkEndpoint() + "/storage/v1/b").contains("fixture-bucket"));
    }
  }

  @Test
  void anInterruptedServiceRefusesCallersUntilRestored() throws Exception {
    try (Network network = Network.newNetwork();
        var gcs = new GcsEmulatorTestContainer(network, "gcs-outage").start()) {
      URI host = gcs.hostEndpoint().resolve("/storage/v1/b");
      assertEquals(
          200,
          HTTP.send(HttpRequest.newBuilder(host).build(), HttpResponse.BodyHandlers.discarding())
              .statusCode());

      gcs.interruptService();
      assertThrows(
          IOException.class,
          () ->
              HTTP.send(
                  HttpRequest.newBuilder(host).timeout(Duration.ofSeconds(5)).build(),
                  HttpResponse.BodyHandlers.discarding()));

      // Restarted, the server answers its network peers again.
      gcs.restoreService();
      assertTrue(fromPeer(network, gcs.networkEndpoint() + "/storage/v1/b").contains("kind"));
    }
  }

  @Test
  void aFixtureNeedsANetworkAndADnsAlias() {
    try (Network network = Network.newNetwork()) {
      assertThrows(NullPointerException.class, () -> new GcsEmulatorTestContainer(null, "gcs"));
      for (String alias : new String[] {null, "", "-gcs", "gcs_fixture", "gcs.fixture"})
        assertThrows(
            IllegalArgumentException.class,
            () -> new GcsEmulatorTestContainer(network, alias),
            String.valueOf(alias));
    }
  }

  @Test
  void anUnstartedOrClosedFixtureRefusesUse() {
    try (Network network = Network.newNetwork()) {
      var gcs = new GcsEmulatorTestContainer(network, "gcs-unstarted");
      assertThrows(IllegalStateException.class, gcs::hostEndpoint);
      assertThrows(IllegalStateException.class, gcs::networkEndpoint);
      assertThrows(IllegalStateException.class, gcs::interruptService);
      assertThrows(IllegalStateException.class, gcs::restoreService);
      gcs.close();
      gcs.close();
      assertEquals(
          "GCS fixture is closed",
          assertThrows(IllegalStateException.class, gcs::start).getMessage());
    }
  }

  @Test
  void aFailedStartClosesTheFixtureAndRethrows() {
    // A network Docker has never heard of: attaching the container to it fails the start.
    String missing = "fm-missing-network-" + UUID.randomUUID();
    Network absent =
        new Network() {
          @Override
          public String getId() {
            return missing;
          }

          @Override
          public void close() {}
        };
    var gcs = new GcsEmulatorTestContainer(absent, "gcs-gone");
    assertThrows(RuntimeException.class, gcs::start);
    assertEquals(
        "GCS fixture is closed",
        assertThrows(IllegalStateException.class, gcs::start).getMessage());
    assertThrows(IllegalStateException.class, gcs::hostEndpoint);
  }
}
