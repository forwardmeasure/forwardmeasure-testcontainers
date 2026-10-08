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
package com.forwardmeasure.testcontainers.kubernetes;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.testcontainers.containers.Container;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.MountableFile;

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

  /** Bind an external fixture read-only into the disposable node before startup. */
  public synchronized KubernetesTestContainer withReadOnlyHostFile(Path source, String target) {
    if (started || closed)
      throw new IllegalStateException("Configure fixture mounts before starting the cluster");
    if (!Files.isRegularFile(source))
      throw new IllegalArgumentException("Fixture must be a regular file");
    container.withFileSystemBind(
        source.toAbsolutePath().toString(),
        target,
        org.testcontainers.containers.BindMode.READ_ONLY);
    return this;
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

  /**
   * Makes a locally-built (host {@code docker images}) image available to this cluster's own
   * containerd, with no registry round-trip - a real Job/Deployment can then reference it by plain
   * {@code image:tag}, no imagePullSecret needed. K3s's own pod network can't see the host Docker
   * daemon's image cache, so a plain {@code docker build} on the host is otherwise invisible here.
   * Mirrors the recipe forwardmeasure-entity-intelligence's own {@code
   * IngestionPipelineFowfK3sVerificationTest} had to hand-roll against a raw {@code K3sContainer}
   * because this class didn't expose one - added here so every {@code @WithKubernetesContainer}
   * consumer gets it for free instead of re-deriving it.
   */
  public void loadImage(String image) {
    ensureStarted();
    Objects.requireNonNull(image, "image");
    try {
      Path tar = Files.createTempFile("k3s-load-image", ".tar");
      try {
        Process save =
            new ProcessBuilder("docker", "save", "-o", tar.toString(), image).inheritIO().start();
        int exitCode = save.waitFor();
        if (exitCode != 0) {
          throw new IllegalStateException("docker save exited " + exitCode + " for " + image);
        }
        String remotePath = "/tmp/" + tar.getFileName();
        container.copyFileToContainer(MountableFile.forHostPath(tar), remotePath);
        Container.ExecResult result =
            container.execInContainer("ctr", "images", "import", remotePath);
        if (result.getExitCode() != 0) {
          throw new IllegalStateException(
              "ctr images import failed for "
                  + image
                  + ": "
                  + result.getStdout()
                  + result.getStderr());
        }
      } finally {
        Files.deleteIfExists(tar);
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted loading image " + image + " into K3s", failure);
    } catch (IOException failure) {
      throw new IllegalStateException("Failed to load image " + image + " into K3s", failure);
    }
  }

  /**
   * Same as {@link #loadImage} (a locally-built image, no registry round-trip), but for a Job/
   * Deployment dispatched against a policy that requires images to be {@code @sha256:}-pinned (this
   * org's own established convention - see {@code KubernetesJobPolicy}/{@code
   * KubernetesDeploymentPolicy}'s own sha256-digest-pinned allowlists) rather than a plain, mutable
   * tag. Returns a real, verified-resolvable digest reference for {@code image} - not a fabricated
   * one: {@code docker save}+{@code ctr images import} alone does *not* make an image resolvable by
   * its own manifest digest (confirmed empirically, 2026-09-24 - a bare {@code ctr run} against a
   * synthesized {@code repo@sha256:<digest>} reference right after import fails with "not found,"
   * even though {@code ctr images ls} already reports that exact digest against the plain-tagged
   * entry); an explicit {@code ctr images tag <plain> <digest-ref>} is required to actually alias
   * the already-imported content under that name, confirmed live to resolve afterward with zero
   * network access.
   *
   * @return {@code image}'s own repository, re-qualified with its real manifest digest (e.g. {@code
   *     forwardmeasure/foo:1.1.0} in, {@code forwardmeasure/foo@sha256:<64 hex>} out) - the string
   *     to use wherever a real Job/Deployment payload needs a digest-pinned reference.
   */
  public String loadImageAndPinDigest(String image) {
    loadImage(image);
    return pinDigest(
        image,
        command -> {
          var result = container.execInContainer(command);
          return new ImageCommandResult(
              result.getExitCode(), result.getStdout(), result.getStderr());
        });
  }

  @FunctionalInterface
  interface ImageCommand {
    ImageCommandResult execute(String... command) throws IOException, InterruptedException;
  }

  record ImageCommandResult(int exitCode, String stdout, String stderr) {}

  /** Command boundary shared by real containerd and deterministic failure-contract tests. */
  static String pinDigest(String image, ImageCommand command) {
    try {
      ImageCommandResult listed = command.execute("ctr", "images", "ls");
      if (listed.exitCode() != 0) {
        throw new IllegalStateException(
            "ctr images ls failed: " + listed.stdout() + listed.stderr());
      }
      String digest = null;
      String qualifiedRef = null;
      for (String line : listed.stdout().split("\n")) {
        if (line.isBlank()) continue;
        String[] fields = line.trim().split("\\s+");
        String ref = fields[0];
        // ctr images ls's own REF column normalizes an unqualified name (this org's own
        // convention throughout - no explicit registry host) onto its fully-qualified docker.io
        // form (confirmed live 2026-09-24: "forwardmeasure/foo:1.1.0" is imported as
        // "docker.io/forwardmeasure/foo:1.1.0") - matching the raw line's own prefix against the
        // unqualified image string therefore never matches; compare the REF field itself instead,
        // exactly or as a path suffix after any registry-host qualification. The qualified form
        // is also what "ctr images tag" itself needs as its own source argument below - the
        // unqualified image string isn't a real, resolvable reference inside containerd at all.
        if (!ref.equals(image) && !ref.endsWith("/" + image)) continue;
        for (String field : fields) {
          if (field.matches("sha256:[0-9a-f]{64}")) {
            digest = field;
            qualifiedRef = ref;
            break;
          }
        }
      }
      if (digest == null) {
        throw new IllegalStateException(
            "Could not find " + image + "'s own manifest digest in: " + listed.stdout());
      }
      String repository =
          qualifiedRef.contains("@")
              ? qualifiedRef.substring(0, qualifiedRef.indexOf('@'))
              : qualifiedRef;
      int lastColon = repository.lastIndexOf(':');
      int lastSlash = repository.lastIndexOf('/');
      if (lastColon > lastSlash) {
        repository = repository.substring(0, lastColon);
      }
      String digestReference = repository + "@" + digest;
      ImageCommandResult tagged =
          command.execute("ctr", "images", "tag", qualifiedRef, digestReference);
      if (tagged.exitCode() != 0) {
        throw new IllegalStateException(
            "ctr images tag failed for "
                + digestReference
                + ": "
                + tagged.stdout()
                + tagged.stderr());
      }
      return digestReference;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted pinning a digest reference for " + image + " in K3s", failure);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Failed to pin a digest reference for " + image + " in K3s", failure);
    }
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
