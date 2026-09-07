package com.forwardmeasure.testcontainers.kubernetes;

import java.util.Objects;
import org.testcontainers.utility.DockerImageName;

/** Immutable configuration for one K3s test container. */
public record KubernetesContainerConfiguration(DockerImageName image) {

  public static final DockerImageName DEFAULT_IMAGE =
      DockerImageName.parse("rancher/k3s:v1.36.4-k3s1");

  public KubernetesContainerConfiguration {
    Objects.requireNonNull(image, "image");
  }

  public static KubernetesContainerConfiguration defaults() {
    return new KubernetesContainerConfiguration(DEFAULT_IMAGE);
  }
}
