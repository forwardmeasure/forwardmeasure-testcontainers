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
package com.forwardmeasure.testcontainers.postgresql;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.testcontainers.utility.DockerImageName;

/** Immutable configuration for one PostgreSQL test container. */
public record PostgreSqlContainerConfiguration(
    DockerImageName image,
    String databaseName,
    String username,
    String password,
    Optional<String> networkId,
    List<String> networkAliases,
    long memoryBytes,
    long memorySwapBytes) {

  public static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("postgres:18-alpine");

  /**
   * Postgres's own default {@code shared_buffers} is 128 MiB, so a 128 MiB cap leaves nothing for
   * backends: once a test filled shared memory across several databases and connection pools, the
   * kernel OOM-killed backends inside the container's memory cgroup and Postgres dropped every open
   * connection (seen 2026-10-03 in the workflow-publisher contract tests).
   */
  public static final long DEFAULT_MEMORY_BYTES = 512L * 1024L * 1024L;

  public static final long DEFAULT_MEMORY_SWAP_BYTES = 1024L * 1024L * 1024L;

  public PostgreSqlContainerConfiguration {
    Objects.requireNonNull(image, "image");
    databaseName = required(databaseName, "databaseName");
    username = required(username, "username");
    password = required(password, "password");
    networkId =
        Objects.requireNonNull(networkId, "networkId").map(value -> required(value, "networkId"));
    networkAliases = List.copyOf(Objects.requireNonNull(networkAliases, "networkAliases"));
    networkAliases.forEach(alias -> required(alias, "networkAlias"));
    if (memoryBytes < 0 || memorySwapBytes < 0) {
      throw new IllegalArgumentException("Memory limits must not be negative");
    }
    if (memorySwapBytes > 0 && memoryBytes > memorySwapBytes) {
      throw new IllegalArgumentException(
          "memorySwapBytes must be greater than or equal to memoryBytes");
    }
  }

  public static PostgreSqlContainerConfiguration defaults() {
    return new PostgreSqlContainerConfiguration(
        DEFAULT_IMAGE,
        "forwardmeasure_test",
        "forwardmeasure",
        "forwardmeasure-test-only",
        Optional.empty(),
        List.of(),
        DEFAULT_MEMORY_BYTES,
        DEFAULT_MEMORY_SWAP_BYTES);
  }

  public PostgreSqlContainerConfiguration withNetwork(String networkId, List<String> aliases) {
    return new PostgreSqlContainerConfiguration(
        image,
        databaseName,
        username,
        password,
        Optional.of(networkId),
        aliases,
        memoryBytes,
        memorySwapBytes);
  }

  @Override
  public String toString() {
    return "PostgreSqlContainerConfiguration[image="
        + image
        + ", databaseName="
        + databaseName
        + ", username="
        + username
        + ", password=<redacted>"
        + ", networkId="
        + networkId
        + ", networkAliases="
        + networkAliases
        + ", memoryBytes="
        + memoryBytes
        + ", memorySwapBytes="
        + memorySwapBytes
        + "]";
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
