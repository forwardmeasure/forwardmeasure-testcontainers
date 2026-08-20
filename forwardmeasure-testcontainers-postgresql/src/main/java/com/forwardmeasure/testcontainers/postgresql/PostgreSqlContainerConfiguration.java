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

  public static final long DEFAULT_MEMORY_BYTES = 128L * 1024L * 1024L;

  public static final long DEFAULT_MEMORY_SWAP_BYTES = 256L * 1024L * 1024L;

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
