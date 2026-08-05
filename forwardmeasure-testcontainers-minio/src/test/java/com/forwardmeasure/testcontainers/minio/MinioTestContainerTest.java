package com.forwardmeasure.testcontainers.minio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class MinioTestContainerTest {

    @Test
    void startsRealMinioAndRedactsCredentials() throws Exception {
        try (var minio = new MinioTestContainer().start()) {
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(minio.hostEndpoint().resolve(
                            "/minio/health/ready")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());

            assertTrue(minio.isRunning());
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300);
            assertFalse(minio.configuration().toString().contains(minio.secretKey()));
        }
    }

    @Test
    void refusesEndpointsBeforeStartup() {
        try (var minio = new MinioTestContainer()) {
            assertThrows(IllegalStateException.class, minio::hostEndpoint);
        }
    }
}
