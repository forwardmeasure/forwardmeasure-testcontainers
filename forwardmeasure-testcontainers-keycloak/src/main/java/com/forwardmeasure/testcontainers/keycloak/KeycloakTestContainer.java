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
package com.forwardmeasure.testcontainers.keycloak;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/** A real Keycloak fixture importing a caller-supplied realm definition. */
public final class KeycloakTestContainer implements AutoCloseable {

  private static final DockerImageName IMAGE = DockerImageName.parse(image());

  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

  private final String realm;

  private final String networkAlias;

  private final GenericContainer<?> container;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

  public KeycloakTestContainer(String realm, String realmJson) {
    this(realm, realmJson, null, null);
  }

  /**
   * Joins {@code network} as {@code alias}, with Keycloak's hostname pinned to the in-network URL
   * ({@code http://<alias>:8080}). Every token then carries {@link #networkIssuer()} as its {@code
   * iss}, whether it was requested from the host through {@link #issuer()}'s mapped port or from
   * another container on the network - so containerized services validate host-minted tokens
   * against the same issuer they discover over the network, with no per-framework issuer override.
   */
  public KeycloakTestContainer(String realm, String realmJson, Network network, String alias) {
    this.realm = required(realm, "realm");
    if ((network == null) != (alias == null)) {
      throw new IllegalArgumentException("network and alias must be given together");
    }
    this.networkAlias = alias == null ? null : required(alias, "alias");
    Objects.requireNonNull(realmJson, "realmJson");
    if (realmJson.isBlank()) {
      throw new IllegalArgumentException("realmJson must not be blank");
    }
    container =
        new GenericContainer<>(IMAGE)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-integration-only")
            .withCopyToContainer(
                Transferable.of(realmJson.getBytes(StandardCharsets.UTF_8), 0444),
                "/opt/keycloak/data/import/realm.json")
            // --features=authzen: enables Keycloak 26.7's OpenID AuthZen
            // Authorization API module (org.keycloak.common.Profile.Feature.AUTHZEN is
            // Type.EXPERIMENTAL, disabled unless explicitly requested). Harmless to enable
            // unconditionally for every caller of this shared fixture: it only adds the
            // /realms/{realm}/authzen/access/v1/{evaluation,evaluations} endpoints on top of the
            // existing Authorization Services engine, it does not change any other behavior.
            .withCommand("start-dev", "--import-realm", "--features=authzen")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/realms/" + this.realm)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    if (network != null) {
      container
          .withNetwork(network)
          .withNetworkAliases(alias)
          .withEnv("KC_HOSTNAME", "http://" + alias + ":8080");
    }
  }

  public KeycloakTestContainer start() {
    container.start();
    return this;
  }

  /**
   * The realm URL reachable from the host (mapped port) - for admin and token calls from the test
   * process. Without a network this is also every token's {@code iss}; with one, see {@link
   * #networkIssuer()}.
   */
  public URI issuer() {
    ensureRunning();
    return URI.create(
        "http://" + container.getHost() + ":" + container.getMappedPort(8080) + "/realms/" + realm);
  }

  /**
   * The realm URL on the network given to {@link #KeycloakTestContainer(String, String, Network,
   * String)} - every token's {@code iss}, and what containers on that network configure as their
   * issuer.
   */
  public URI networkIssuer() {
    if (networkAlias == null) {
      throw new IllegalStateException("This Keycloak test container was not started on a network");
    }
    return URI.create("http://" + networkAlias + ":8080/realms/" + realm);
  }

  public String passwordToken(String clientId, String username, String password) {
    return passwordToken(issuer(), clientId, username, password);
  }

  /**
   * Mints a genuine same-key token through the alternate loopback hostname for issuer-rejection
   * contracts. Requires a local Docker host and an unpinned issuer (no caller-supplied network).
   */
  public String passwordTokenWithAlternateIssuer(
      String clientId, String username, String password) {
    URI expected = issuer();
    if (networkAlias != null
        || !("localhost".equals(expected.getHost()) || "127.0.0.1".equals(expected.getHost()))) {
      throw new IllegalStateException(
          "Alternate issuer requires an unpinned local Keycloak fixture");
    }
    String host = "localhost".equals(expected.getHost()) ? "127.0.0.1" : "localhost";
    URI alternate =
        URI.create(
            expected.getScheme() + "://" + host + ":" + expected.getPort() + expected.getPath());
    return passwordToken(alternate, clientId, username, password);
  }

  private String passwordToken(URI issuer, String clientId, String username, String password) {
    return token(
        issuer,
        "grant_type=password&client_id="
            + encode(clientId)
            + "&username="
            + encode(username)
            + "&password="
            + encode(password)
            + "&scope=openid");
  }

  public String clientCredentialsToken(String clientId, String clientSecret) {
    return token(
        "grant_type=client_credentials&client_id="
            + encode(clientId)
            + "&client_secret="
            + encode(clientSecret)
            + "&scope=openid");
  }

  private String token(String body) {
    return token(issuer(), body);
  }

  private String token(URI issuer, String body) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "Keycloak token request returned HTTP "
                + response.statusCode()
                + ": "
                + response.body());
      }
      Matcher matcher = ACCESS_TOKEN.matcher(response.body());
      if (!matcher.find()) {
        throw new IllegalStateException("Keycloak token response has no access_token");
      }
      return matcher.group(1);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while obtaining a Keycloak token", interrupted);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Unable to obtain a Keycloak token", failure);
    }
  }

  private void ensureRunning() {
    if (!container.isRunning()) {
      throw new IllegalStateException("Keycloak test container is not running");
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(required(value, "form value"), StandardCharsets.UTF_8);
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String image() {
    Properties properties = new Properties();
    try (var stream =
        KeycloakTestContainer.class.getResourceAsStream("/keycloak-testcontainer.properties")) {
      if (stream == null) {
        throw new IllegalStateException("Missing keycloak-testcontainer.properties");
      }
      properties.load(stream);
      return required(properties.getProperty("image"), "image");
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(
          "Unable to load the Keycloak test image configuration", failure);
    }
  }

  @Override
  public void close() {
    container.stop();
  }
}
