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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
