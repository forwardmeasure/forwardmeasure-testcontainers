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
package com.forwardmeasure.testcontainers.cassandra;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.utility.DockerImageName;

/**
 * Immutable configuration for one Cassandra test container.
 *
 * @param image the Cassandra image; any image compatible with the official {@code cassandra} one
 * @param maxHeapSize the image's {@code MAX_HEAP_SIZE} ({@code -Xmx}), e.g. {@code 512M}
 * @param heapNewSize the image's {@code HEAP_NEWSIZE}; {@code cassandra-env.sh} requires it to be
 *     set together with {@code maxHeapSize}
 * @param memoryBytes the container's cgroup memory limit; 0 applies none
 * @param memorySwapBytes the container's memory-plus-swap limit; 0 leaves Docker's default
 * @param passwordAuthentication turns on {@code PasswordAuthenticator} and {@code
 *     CassandraAuthorizer} with the default {@code cassandra}/{@code cassandra} superuser
 * @param configurationFile a classpath resource copied over {@code /etc/cassandra/cassandra.yaml}
 *     before start, so a caller chooses the partitioner, SSL, client authentication and so on. The
 *     image's entrypoint still rewrites the address, seed and snitch keys in it, so the file must
 *     keep those keys (commented-out {@code broadcast_address}/{@code broadcast_rpc_address} lines
 *     included), as the image's own file does
 * @param startupTimeout how long to wait for CQL to answer
 * @param networkId a caller-owned Docker network to attach to
 * @param networkAliases DNS aliases on that network
 * @param tls optional server/client stores; retained in the shared-fixture identity
 */
public record CassandraContainerConfiguration(
    DockerImageName image,
    String maxHeapSize,
    String heapNewSize,
    long memoryBytes,
    long memorySwapBytes,
    boolean passwordAuthentication,
    Optional<String> configurationFile,
    Duration startupTimeout,
    Optional<String> networkId,
    List<String> networkAliases,
    Optional<CassandraTlsConfiguration> tls) {

  public static final String DEFAULT_IMAGE_NAME = "cassandra:5.0.5";

  public static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse(DEFAULT_IMAGE_NAME);

  public static final String DEFAULT_MAX_HEAP_SIZE = "512M";

  public static final String DEFAULT_HEAP_NEW_SIZE = "100M";

  /** Two GiB: room for the default 512M heap plus Cassandra's substantial off-heap use. */
  public static final long DEFAULT_MEMORY_BYTES = 2L * 1024 * 1024 * 1024;

  public static final long DEFAULT_MEMORY_SWAP_BYTES = 0L;

  public static final long DEFAULT_STARTUP_TIMEOUT_SECONDS = 180L;

  public static final Duration DEFAULT_STARTUP_TIMEOUT =
      Duration.ofSeconds(DEFAULT_STARTUP_TIMEOUT_SECONDS);

  private static final Pattern HEAP_SIZE = Pattern.compile("([1-9][0-9]{0,8})([KkMmGg])");

  public CassandraContainerConfiguration {
    Objects.requireNonNull(image, "image");
    long maxHeapBytes = heapBytes(maxHeapSize, "maxHeapSize");
    long heapNewBytes = heapBytes(heapNewSize, "heapNewSize");
    if (heapNewBytes >= maxHeapBytes) {
      throw new IllegalArgumentException("heapNewSize must be smaller than maxHeapSize");
    }
    if (memoryBytes < 0 || memorySwapBytes < 0) {
      throw new IllegalArgumentException("Memory limits must not be negative");
    }
    if (memorySwapBytes > 0 && memoryBytes > memorySwapBytes) {
      throw new IllegalArgumentException(
          "memorySwapBytes must be greater than or equal to memoryBytes");
    }
    if (memoryBytes > 0 && maxHeapBytes >= memoryBytes) {
      // The JVM would be OOM-killed by its own cgroup before the heap filled.
      throw new IllegalArgumentException("maxHeapSize must be smaller than memoryBytes");
    }
    configurationFile =
        Objects.requireNonNull(configurationFile, "configurationFile")
            .map(value -> required(value, "configurationFile"));
    Objects.requireNonNull(startupTimeout, "startupTimeout");
    if (startupTimeout.isNegative() || startupTimeout.isZero()) {
      throw new IllegalArgumentException("startupTimeout must be positive");
    }
    networkId =
        Objects.requireNonNull(networkId, "networkId").map(value -> required(value, "networkId"));
    networkAliases = List.copyOf(Objects.requireNonNull(networkAliases, "networkAliases"));
    networkAliases.forEach(alias -> required(alias, "networkAlias"));
    tls = Objects.requireNonNull(tls, "tls");
  }

  /** Source/binary-compatible constructor for existing plaintext fixtures. */
  public CassandraContainerConfiguration(
      DockerImageName image,
      String maxHeapSize,
      String heapNewSize,
      long memoryBytes,
      long memorySwapBytes,
      boolean passwordAuthentication,
      Optional<String> configurationFile,
      Duration startupTimeout,
      Optional<String> networkId,
      List<String> networkAliases) {
    this(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        passwordAuthentication,
        configurationFile,
        startupTimeout,
        networkId,
        networkAliases,
        Optional.empty());
  }

  public CassandraContainerConfiguration withTls(CassandraTlsConfiguration tls) {
    return new CassandraContainerConfiguration(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        passwordAuthentication,
        configurationFile,
        startupTimeout,
        networkId,
        networkAliases,
        Optional.of(tls));
  }

  /** The fixture's long-standing settings: {@code cassandra:5.0.5}, 512M heap, 2 GiB cap. */
  public static CassandraContainerConfiguration defaults() {
    return new CassandraContainerConfiguration(
        DEFAULT_IMAGE,
        DEFAULT_MAX_HEAP_SIZE,
        DEFAULT_HEAP_NEW_SIZE,
        DEFAULT_MEMORY_BYTES,
        DEFAULT_MEMORY_SWAP_BYTES,
        false,
        Optional.empty(),
        DEFAULT_STARTUP_TIMEOUT,
        Optional.empty(),
        List.of(),
        Optional.empty());
  }

  public CassandraContainerConfiguration withImage(DockerImageName image) {
    return new CassandraContainerConfiguration(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        passwordAuthentication,
        configurationFile,
        startupTimeout,
        networkId,
        networkAliases,
        tls);
  }

  /** Both heap settings at once: {@code cassandra-env.sh} only accepts them as a pair. */
  public CassandraContainerConfiguration withHeap(String maxHeapSize, String heapNewSize) {
    return new CassandraContainerConfiguration(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        passwordAuthentication,
        configurationFile,
        startupTimeout,
        networkId,
        networkAliases,
        tls);
  }

  public CassandraContainerConfiguration withMemory(long memoryBytes, long memorySwapBytes) {
    return new CassandraContainerConfiguration(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        passwordAuthentication,
        configurationFile,
        startupTimeout,
        networkId,
        networkAliases,
        tls);
  }

  public CassandraContainerConfiguration withPasswordAuthentication(boolean enabled) {
    return new CassandraContainerConfiguration(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        enabled,
        configurationFile,
        startupTimeout,
        networkId,
        networkAliases,
        tls);
  }

  /** A classpath resource that replaces {@code /etc/cassandra/cassandra.yaml}. */
  public CassandraContainerConfiguration withConfigurationFile(String classpathResource) {
    return new CassandraContainerConfiguration(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        passwordAuthentication,
        Optional.of(classpathResource),
        startupTimeout,
        networkId,
        networkAliases,
        tls);
  }

  public CassandraContainerConfiguration withStartupTimeout(Duration startupTimeout) {
    return new CassandraContainerConfiguration(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        passwordAuthentication,
        configurationFile,
        startupTimeout,
        networkId,
        networkAliases,
        tls);
  }

  /** Attaches to a caller-owned network, which the fixture never removes. */
  public CassandraContainerConfiguration withNetwork(String networkId, List<String> aliases) {
    return new CassandraContainerConfiguration(
        image,
        maxHeapSize,
        heapNewSize,
        memoryBytes,
        memorySwapBytes,
        passwordAuthentication,
        configurationFile,
        startupTimeout,
        Optional.of(networkId),
        aliases,
        tls);
  }

  @Override
  public String toString() {
    // Never include TLS passwords, even in nested fixture diagnostics.
    return "CassandraContainerConfiguration[image="
        + image
        + ", maxHeapSize="
        + maxHeapSize
        + ", heapNewSize="
        + heapNewSize
        + ", memoryBytes="
        + memoryBytes
        + ", memorySwapBytes="
        + memorySwapBytes
        + ", passwordAuthentication="
        + passwordAuthentication
        + ", configurationFile="
        + configurationFile
        + ", startupTimeout="
        + startupTimeout
        + ", networkId="
        + networkId
        + ", tls="
        + tls
        + ", networkAliases="
        + networkAliases
        + "]";
  }

  private static long heapBytes(String value, String name) {
    required(value, name);
    Matcher matcher = HEAP_SIZE.matcher(value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          name + " must be a JVM size such as 512M or 1G, not " + value);
    }
    long amount = Long.parseLong(matcher.group(1));
    return switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
      case "K" -> amount * 1024;
      case "M" -> amount * 1024 * 1024;
      default -> amount * 1024 * 1024 * 1024;
    };
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
