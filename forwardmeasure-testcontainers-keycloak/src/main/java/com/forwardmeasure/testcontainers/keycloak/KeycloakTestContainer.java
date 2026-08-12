package com.forwardmeasure.testcontainers.keycloak;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/** A real Keycloak fixture importing a caller-supplied realm definition. */
public final class KeycloakTestContainer implements AutoCloseable {

    private static final DockerImageName IMAGE = DockerImageName.parse(image());

    private static final Pattern ACCESS_TOKEN = Pattern.compile(
            "\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final String realm;

    private final GenericContainer<?> container;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public KeycloakTestContainer(String realm, String realmJson) {
        this.realm = required(realm, "realm");
        Objects.requireNonNull(realmJson, "realmJson");
        if (realmJson.isBlank()) {
            throw new IllegalArgumentException("realmJson must not be blank");
        }
        container = new GenericContainer<>(IMAGE)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-integration-only")
                .withCopyToContainer(
                        Transferable.of(realmJson.getBytes(StandardCharsets.UTF_8), 0444),
                        "/opt/keycloak/data/import/realm.json")
                .withCommand("start-dev", "--import-realm")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/" + this.realm)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    public KeycloakTestContainer start() {
        container.start();
        return this;
    }

    public URI issuer() {
        ensureRunning();
        return URI.create("http://" + container.getHost() + ":"
                + container.getMappedPort(8080) + "/realms/" + realm);
    }

    public String passwordToken(
            String clientId,
            String username,
            String password) {
        return token("grant_type=password&client_id=" + encode(clientId)
                + "&username=" + encode(username)
                + "&password=" + encode(password)
                + "&scope=openid");
    }

    public String clientCredentialsToken(String clientId, String clientSecret) {
        return token("grant_type=client_credentials&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&scope=openid");
    }

    private String token(String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            issuer() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Keycloak token request returned HTTP "
                                + response.statusCode() + ": " + response.body());
            }
            Matcher matcher = ACCESS_TOKEN.matcher(response.body());
            if (!matcher.find()) {
                throw new IllegalStateException(
                        "Keycloak token response has no access_token");
            }
            return matcher.group(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while obtaining a Keycloak token", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "Unable to obtain a Keycloak token", failure);
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
        try (var stream = KeycloakTestContainer.class.getResourceAsStream(
                "/keycloak-testcontainer.properties")) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing keycloak-testcontainer.properties");
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
