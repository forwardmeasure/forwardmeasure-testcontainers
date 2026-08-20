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

  public static final DockerImageName DEFAULT_IMAGE =
      DockerImageName.parse("minio/minio:RELEASE.2024-01-16T16-07-38Z");

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
