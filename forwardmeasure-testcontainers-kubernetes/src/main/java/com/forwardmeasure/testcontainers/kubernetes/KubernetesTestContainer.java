package com.forwardmeasure.testcontainers.kubernetes;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.Objects;
import org.testcontainers.k3s.K3sContainer;

/** Explicitly owned single-node K3s Testcontainer with a real Kubernetes API server. */
public final class KubernetesTestContainer implements AutoCloseable {

  private final KubernetesContainerConfiguration configuration;

  private final K3sContainer container;

  private boolean started;

  private boolean closed;

  public KubernetesTestContainer() {
    this(KubernetesContainerConfiguration.defaults());
  }

  public KubernetesTestContainer(KubernetesContainerConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.container = new K3sContainer(configuration.image());
  }

  public synchronized KubernetesTestContainer start() {
    if (closed) {
      throw new IllegalStateException("Kubernetes test container has been closed");
    }
    if (!started) {
      try {
        container.start();
        started = true;
      } catch (RuntimeException | Error failure) {
        container.close();
        closed = true;
        throw failure;
      }
    }
    return this;
  }

  /** The raw kubeconfig YAML for the running cluster, reachable from the test host. */
  public String kubeConfigYaml() {
    ensureStarted();
    return container.getKubeConfigYaml();
  }

  /**
   * A new {@link KubernetesClient} built from this cluster's kubeconfig. Each call returns an
   * independent client - callers own closing whatever they create.
   */
  public KubernetesClient createClient() {
    return new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(kubeConfigYaml()))
        .build();
  }

  public KubernetesContainerConfiguration configuration() {
    return configuration;
  }

  public boolean isRunning() {
    return started && container.isRunning();
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      container.close();
      started = false;
      closed = true;
    }
  }

  private void ensureStarted() {
    if (!isRunning()) {
      throw new IllegalStateException("Kubernetes test container is not running");
    }
  }
}
