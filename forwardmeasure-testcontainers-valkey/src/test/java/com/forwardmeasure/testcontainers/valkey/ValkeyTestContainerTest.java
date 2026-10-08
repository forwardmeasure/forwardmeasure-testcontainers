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
package com.forwardmeasure.testcontainers.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.Network;

class ValkeyTestContainerTest {
  @Test
  @Timeout(60)
  void authenticatedDataSurvivesClientReconnectAndRepeatedStart() {
    try (var network = Network.newNetwork();
        var valkey = new ValkeyTestContainer(network, "fact-window", "fixture-password")) {
      assertThrows(IllegalStateException.class, valkey::port);
      valkey.start();
      int port = valkey.port();
      var client =
          RedisClient.create(
              RedisURI.Builder.redis(valkey.host(), port)
                  .withPassword(valkey.password().toCharArray())
                  .build());
      try {
        try (var first = client.connect()) {
          assertEquals(1L, first.sync().rpush("tenant-a:window", "first-fact"));
        }
        valkey.start();
        assertEquals(port, valkey.port());
        try (var second = client.connect()) {
          assertEquals(
              java.util.List.of("first-fact"), second.sync().lrange("tenant-a:window", 0, -1));
        }
      } finally {
        client.shutdown();
      }
      valkey.close();
      assertThrows(IllegalStateException.class, valkey::start);
      assertThrows(IllegalStateException.class, valkey::host);
    }
  }

  @Test
  void rejectsUnauthenticatedConfigurationAndUseAfterClose() {
    assertThrows(IllegalArgumentException.class, () -> new ValkeyTestContainer(" "));
    try (var network = Network.newNetwork()) {
      assertThrows(
          IllegalArgumentException.class, () -> new ValkeyTestContainer(network, null, "password"));
      assertThrows(
          IllegalArgumentException.class, () -> new ValkeyTestContainer(network, " ", "password"));
    }
    var unused = new ValkeyTestContainer("password");
    unused.close();
    unused.close();
    assertThrows(IllegalStateException.class, unused::start);
  }

  @Test
  @Timeout(60)
  void rejectsMissingAndIncorrectCredentials() {
    try (var valkey = new ValkeyTestContainer("fixture-password").start()) {
      for (String password : new String[] {"", "wrong-password"}) {
        var uri = RedisURI.Builder.redis(valkey.host(), valkey.port());
        if (!password.isEmpty()) uri.withPassword(password.toCharArray());
        var client = RedisClient.create(uri.build());
        try {
          assertThrows(
              RedisException.class,
              () -> {
                try (var connection = client.connect()) {
                  connection.sync().ping();
                }
              });
        } finally {
          client.shutdown();
        }
      }
    }
  }
}
