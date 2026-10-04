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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }
  }

  @Test
  void refusesEndpointsBeforeStartup() {
    try (var openSearch = new OpenSearchTestContainer()) {
      LOGGER.info("Confirming hostEndpoint() rejects a not-yet-started container");
      assertThrows(IllegalStateException.class, openSearch::hostEndpoint);
    }
  }
}
