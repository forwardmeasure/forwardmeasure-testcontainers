package com.forwardmeasure.testcontainers.kafka;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.testcontainers.utility.DockerImageName;

/** Immutable configuration for one Kafka test container. */
public record KafkaContainerConfiguration(
    DockerImageName image,
    Optional<String> networkId,
    List<String> networkAliases,
    boolean hostDockerInternalListenerEnabled) {

  // Matches forwardmeasure-platform's own kafka.version BOM pin (4.3.1) - the real kafka-clients
  // version this whole ecosystem's production code already targets. Never invent a separate
  // version here; keep this in lockstep with that pin.
  public static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("apache/kafka:4.3.1");

  public KafkaContainerConfiguration {
    Objects.requireNonNull(image, "image");
    networkId =
        Objects.requireNonNull(networkId, "networkId").map(value -> required(value, "networkId"));
    networkAliases = List.copyOf(Objects.requireNonNull(networkAliases, "networkAliases"));
    networkAliases.forEach(alias -> required(alias, "networkAlias"));
  }

  /**
   * Pre-{@code hostDockerInternalListenerEnabled} shape, kept working for every existing caller.
   */
  public KafkaContainerConfiguration(
      DockerImageName image, Optional<String> networkId, List<String> networkAliases) {
    this(image, networkId, networkAliases, false);
  }

  public static KafkaContainerConfiguration defaults() {
    return new KafkaContainerConfiguration(DEFAULT_IMAGE, Optional.empty(), List.of(), false);
  }

  public KafkaContainerConfiguration withNetwork(String networkId, List<String> aliases) {
    return new KafkaContainerConfiguration(
        image, Optional.of(networkId), aliases, hostDockerInternalListenerEnabled);
  }

  /**
   * A real, additional listener - advertised as {@code host.docker.internal:<mappedPort>} rather
   * than {@link KafkaTestContainer#bootstrapServers()}'s own {@code getHost()} (typically
   * "localhost") - needed because Kafka's client protocol reconnects to whatever address the
   * broker's own metadata response advertises, and a {@code pod.spec.hostAliases} entry can't
   * override the meaning of "localhost" itself (which always resolves to the pod's own loopback,
   * unlike a real hostname such as {@code host.docker.internal}). Confirmed live (2026-09-21): a
   * real K3s pod given only the default bootstrap listener bootstraps fine (its own hostAliases
   * entry resolves the initial connection) but then times out reconnecting to whatever the broker
   * advertises, because that's always {@code localhost:<port>} - unlike {@code
   * OpenSearchTestContainer}, whose plain HTTP protocol has no such second, broker-driven redirect
   * step, so this gap is specific to Kafka's own client protocol.
   */
  public KafkaContainerConfiguration withHostDockerInternalListener() {
    return new KafkaContainerConfiguration(image, networkId, networkAliases, true);
  }

  @Override
  public String toString() {
    return "KafkaContainerConfiguration[image="
        + image
        + ", networkId="
        + networkId
        + ", networkAliases="
        + networkAliases
        + ", hostDockerInternalListenerEnabled="
        + hostDockerInternalListenerEnabled
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
