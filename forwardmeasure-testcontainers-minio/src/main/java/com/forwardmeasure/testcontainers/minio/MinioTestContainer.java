package com.forwardmeasure.testcontainers.minio;

import java.net.URI;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** Explicitly owned MinIO Testcontainer with host and network endpoints. */
public final class MinioTestContainer implements AutoCloseable {

  public static final int API_PORT = 9000;

  private final MinioContainerConfiguration configuration;

  private final GenericContainer<?> container;

  private boolean started;

  private boolean closed;

  public MinioTestContainer() {
    this(MinioContainerConfiguration.defaults());
  }

  public MinioTestContainer(MinioContainerConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.container = configure(configuration);
  }

  public synchronized MinioTestContainer start() {
    if (closed) {
      throw new IllegalStateException("MinIO test container has been closed");
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

  public URI hostEndpoint() {
    ensureStarted();
    return URI.create("http://" + container.getHost() + ':' + container.getMappedPort(API_PORT));
  }

  public URI networkEndpoint() {
    ensureStarted();
    String alias =
        configuration.networkAliases().stream()
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("No MinIO container network alias is configured"));
    return URI.create("http://" + alias + ':' + API_PORT);
  }

  public String accessKey() {
    return configuration.accessKey();
  }

  public String secretKey() {
    return configuration.secretKey();
  }

  public String containerName() {
    ensureStarted();
    return container.getContainerName();
  }

  public MinioContainerConfiguration configuration() {
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

  private GenericContainer<?> configure(MinioContainerConfiguration settings) {
    GenericContainer<?> configured =
        new GenericContainer<>(settings.image())
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", settings.accessKey())
            .withEnv("MINIO_ROOT_PASSWORD", settings.secretKey())
            .withExposedPorts(API_PORT)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(API_PORT));
    if (settings.memoryBytes() > 0 || settings.memorySwapBytes() > 0) {
      configured.withCreateContainerCmdModifier(
          command -> {
            if (settings.memoryBytes() > 0) {
              command.getHostConfig().withMemory(settings.memoryBytes());
            }
            if (settings.memorySwapBytes() > 0) {
              command.getHostConfig().withMemorySwap(settings.memorySwapBytes());
            }
          });
    }
    settings.networkId().ifPresent(networkId -> configured.withNetwork(existingNetwork(networkId)));
    if (!settings.networkAliases().isEmpty()) {
      configured.withNetworkAliases(settings.networkAliases().toArray(String[]::new));
    }
    return configured;
  }

  private Network existingNetwork(String networkId) {
    return new Network() {
      @Override
      public String getId() {
        return networkId;
      }

      @Override
      public void close() {
        // Caller owns the network lifecycle.
      }
    };
  }

  private void ensureStarted() {
    if (!isRunning()) {
      throw new IllegalStateException("MinIO test container is not running");
    }
  }
}
