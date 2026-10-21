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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** One OpenSearch per configuration per JVM, which no individual test can stop. */
class OpenSearchSharedContainerTest {

  @Test
  void concurrentCallersShareOneRunningInstanceThatTestsCannotClose() throws Exception {
    var configuration = OpenSearchContainerConfiguration.defaults();
    int callers = 4;
    var ready = new CountDownLatch(callers);
    List<Future<OpenSearchTestContainer>> results = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        Callable<OpenSearchTestContainer> call =
            () -> {
              ready.countDown();
              ready.await();
              return OpenSearchTestContainers.shared(configuration);
            };
        results.add(executor.submit(call));
      }
      var shared = results.get(0).get();
      for (var result : results) {
        // Catches a race that starts several containers for one configuration.
        assertSame(shared, result.get());
      }
      assertTrue(shared.isShared());
      String name = shared.containerName();
      // An equal but distinct configuration value finds the same instance.
      assertSame(
          shared,
          OpenSearchTestContainers.shared(
              new OpenSearchContainerConfiguration(
                  configuration.image(),
                  false,
                  Optional.empty(),
                  List.of(),
                  configuration.memoryBytes(),
                  configuration.memorySwapBytes())));

      // Closing it from a test - directly or through try-with-resources - must not stop it.
      shared.close();
      try (var borrowed = OpenSearchTestContainers.shared(configuration)) {
        assertSame(shared, borrowed);
      }
      assertTrue(shared.isRunning());
      assertEquals(name, shared.containerName());
      try (var client = HttpClient.newHttpClient()) {
        var health =
            client.send(
                HttpRequest.newBuilder(shared.hostEndpoint().resolve("/_cluster/health"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(), health.body());
      }
      assertSame(shared, shared.start());

      // Owned fixtures still close.
      var owned = new OpenSearchTestContainer();
      assertFalse(owned.isShared());
      owned.close();
      assertThrows(IllegalStateException.class, owned::start);
    }
  }
}
