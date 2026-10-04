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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.testcontainers.utility.DockerImageName;

/** Immutable configuration for one OpenSearch test container. */
public record OpenSearchContainerConfiguration(
    DockerImageName image,
    boolean securityEnabled,
    Optional<String> networkId,
    List<String> networkAliases,
    long memoryBytes,
    long memorySwapBytes) {

  public static final DockerImageName DEFAULT_IMAGE =
      DockerImageName.parse("forwardmeasure/opensearch:3.8.0");

  /**
   * Unconstrained by default (0 = no cgroup memory limit applied - see {@link
   * OpenSearchTestContainer}'s own private {@code configure(...)} method) - matches every real,
   * already-working use of this exact image across this codebase (fei's {@code
   * SimpleSourceIngestionWorkerIntegrationTest}/{@code
   * ScreeningMatchServiceIntegrationTest}/deployment-leaf {@code ScreeningResourceTest}s all run it
   * with no memory cap at all). An earlier version of this default hard-capped it at 512MB/1GB - a
   * guessed number, confirmed wrong the first time this module was actually run: the container was
   * OOMKilled before its own wait strategy could ever see it become ready. OpenSearch's real JVM
   * footprint at startup is large and image/version-dependent; guessing a "safe-looking" number is
   * exactly the failure mode that caused this, so the real fix is not a bigger guess - it's
   * matching the one convention already proven to work everywhere else this image is used.
   */
  public static final long DEFAULT_MEMORY_BYTES = 0L;

  public static final long DEFAULT_MEMORY_SWAP_BYTES = 0L;

  public OpenSearchContainerConfiguration {
    Objects.requireNonNull(image, "image");
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

  /** Security disabled by default - matches every real consumer's own actual usage today. */
  public static OpenSearchContainerConfiguration defaults() {
    return new OpenSearchContainerConfiguration(
        DEFAULT_IMAGE,
        false,
        Optional.empty(),
        List.of(),
        DEFAULT_MEMORY_BYTES,
        DEFAULT_MEMORY_SWAP_BYTES);
  }

  public OpenSearchContainerConfiguration withNetwork(String networkId, List<String> aliases) {
    return new OpenSearchContainerConfiguration(
        image, securityEnabled, Optional.of(networkId), aliases, memoryBytes, memorySwapBytes);
  }

  @Override
  public String toString() {
    return "OpenSearchContainerConfiguration[image="
        + image
        + ", securityEnabled="
        + securityEnabled
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
