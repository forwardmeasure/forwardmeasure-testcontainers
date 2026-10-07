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
package com.forwardmeasure.testcontainers.minio;

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

class MinioTestContainerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(MinioTestContainerTest.class);

  @Test
  void startsRealMinioAndRedactsCredentials() throws Exception {
    try (var minio = new MinioTestContainer().start()) {
      LOGGER.info("Started {} at {}", minio.containerName(), minio.hostEndpoint());

      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(minio.hostEndpoint().resolve("/minio/health/ready"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding());
      LOGGER.info("GET /minio/health/ready -> {}", response.statusCode());

      assertTrue(minio.isRunning());
      assertTrue(response.statusCode() >= 200 && response.statusCode() < 300);
      assertFalse(minio.configuration().toString().contains(minio.secretKey()));
    }
  }

  @Test
  void refusesEndpointsBeforeStartup() {
    try (var minio = new MinioTestContainer()) {
      LOGGER.info("Confirming hostEndpoint() rejects a not-yet-started container");
      assertThrows(IllegalStateException.class, minio::hostEndpoint);
    }
  }

  @Test
  void callerOwnedNetworkAndAliasSurviveFixtureClosure() throws Exception {
    try (var network = org.testcontainers.containers.Network.newNetwork()) {
      var defaults = MinioContainerConfiguration.defaults();
      var configuration =
          new MinioContainerConfiguration(
                  defaults.image(),
                  defaults.accessKey(),
                  defaults.secretKey(),
                  java.util.Optional.empty(),
                  java.util.List.of(),
                  0,
                  0)
              .withNetwork(network.getId(), java.util.List.of("fixture-minio"));
      var minio = new MinioTestContainer(configuration);
      try (minio) {
        assertSame(minio, minio.start());
        var name = minio.containerName();
        assertSame(minio, minio.start());
        assertEquals(name, minio.containerName(), "Repeated start must reuse the owned container");
        assertEquals(defaults.accessKey(), minio.accessKey());
        try (var client =
            new org.testcontainers.containers.GenericContainer<>("alpine:3.17")
                .withNetwork(network)
                .withCommand("sleep", "infinity")) {
          client.start();
          var result =
              client.execInContainer(
                  "wget",
                  "-qO-",
                  minio.networkEndpoint().resolve("/minio/health/ready").toString());
          assertEquals(0, result.getExitCode(), result.getStderr());
        }
      }
      minio.close();
      assertFalse(minio.isRunning());
      assertThrows(IllegalStateException.class, minio::start);
      assertThrows(IllegalStateException.class, minio::networkEndpoint);
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
  void defaultFixtureCannotAdvertiseAnUnconfiguredNetworkEndpoint() {
    try (var minio = new MinioTestContainer().start()) {
      assertThrows(IllegalStateException.class, minio::networkEndpoint);
    }
  }

  @Test
  void rejectsInvalidCredentialsNetworksAndResourceLimitsBeforeStartingDocker() {
    var defaults = MinioContainerConfiguration.defaults();
    for (String key : java.util.List.of(" ", "ab")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new MinioContainerConfiguration(
                  defaults.image(),
                  key,
                  defaults.secretKey(),
                  java.util.Optional.empty(),
                  java.util.List.of(),
                  0,
                  0));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MinioContainerConfiguration(
                defaults.image(),
                defaults.accessKey(),
                "short",
                java.util.Optional.empty(),
                java.util.List.of(),
                0,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () -> defaults.withNetwork(" ", java.util.List.of("alias")));
    assertThrows(
        IllegalArgumentException.class,
        () -> defaults.withNetwork("network", java.util.List.of(" ")));
    for (long[] limits : new long[][] {{-1, 0}, {0, -1}, {512, 256}}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new MinioContainerConfiguration(
                  defaults.image(),
                  defaults.accessKey(),
                  defaults.secretKey(),
                  java.util.Optional.empty(),
                  java.util.List.of(),
                  limits[0],
                  limits[1]));
    }
  }
}
