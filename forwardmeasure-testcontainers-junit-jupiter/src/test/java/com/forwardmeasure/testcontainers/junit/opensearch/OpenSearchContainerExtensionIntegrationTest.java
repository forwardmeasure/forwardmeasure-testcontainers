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
