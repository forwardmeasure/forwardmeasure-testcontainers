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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.testcontainers.utility.DockerImageName;

/** Immutable configuration for one MinIO test container. */
public record MinioContainerConfiguration(
    DockerImageName image,
    String accessKey,
    String secretKey,
    Optional<String> networkId,
    List<String> networkAliases,
    long memoryBytes,
    long memorySwapBytes) {

  /**
   * PGSTY Silo (https://github.com/pgsty/silo): the community-maintained fork of the MinIO server,
   * published to Docker Hub with pinned release tags. MinIO itself publishes no anonymously
   * pullable community image any more - Docker Hub's {@code minio/minio} is gone and {@code
   * quay.io/minio/minio} answers 401 (both confirmed 2026-10-02). Silo keeps MinIO's interface:
   * {@code server /data}, {@code MINIO_ROOT_USER}/{@code MINIO_ROOT_PASSWORD}, port 9000 and {@code
   * /minio/health/ready} (verified against this tag 2026-10-02).
   */
  public static final DockerImageName DEFAULT_IMAGE =
      DockerImageName.parse("docker.io/pgsty/silo:RELEASE.2026-09-16T00-00-00Z")
          .asCompatibleSubstituteFor("minio/minio");

  public static final long DEFAULT_MEMORY_BYTES = 256L * 1024L * 1024L;

  public static final long DEFAULT_MEMORY_SWAP_BYTES = 512L * 1024L * 1024L;

  public MinioContainerConfiguration {
    Objects.requireNonNull(image, "image");
    accessKey = required(accessKey, "accessKey");
    secretKey = required(secretKey, "secretKey");
    if (accessKey.length() < 3) {
      throw new IllegalArgumentException("accessKey must contain at least 3 characters");
    }
    if (secretKey.length() < 8) {
      throw new IllegalArgumentException("secretKey must contain at least 8 characters");
    }
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

  public static MinioContainerConfiguration defaults() {
    return new MinioContainerConfiguration(
        DEFAULT_IMAGE,
        "forwardmeasure",
        "forwardmeasure-test-only",
        Optional.empty(),
        List.of(),
        DEFAULT_MEMORY_BYTES,
        DEFAULT_MEMORY_SWAP_BYTES);
  }

  public MinioContainerConfiguration withNetwork(String networkId, List<String> aliases) {
    return new MinioContainerConfiguration(
        image, accessKey, secretKey, Optional.of(networkId), aliases, memoryBytes, memorySwapBytes);
  }

  @Override
  public String toString() {
    return "MinioContainerConfiguration[image="
        + image
        + ", accessKey="
        + accessKey
        + ", secretKey=<redacted>"
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
