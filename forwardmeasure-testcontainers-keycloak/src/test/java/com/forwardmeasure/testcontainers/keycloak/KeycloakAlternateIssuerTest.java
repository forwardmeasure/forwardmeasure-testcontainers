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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * The alternate issuer for issuer-rejection contracts: the other loopback name, same port and realm
 * path. Which name Docker reports depends on the host, so each case is checked here directly.
 */
class KeycloakAlternateIssuerTest {
  private static final String REALM_PATH = "/realms/forwardmeasure-test";

  @Test
  void localhostBecomesTheLoopbackAddress() {
    assertEquals(
        URI.create("http://127.0.0.1:32768" + REALM_PATH),
        KeycloakTestContainer.alternateIssuer(
            URI.create("http://localhost:32768" + REALM_PATH), false));
  }

  @Test
  void theLoopbackAddressBecomesLocalhost() {
    assertEquals(
        URI.create("http://localhost:32768" + REALM_PATH),
        KeycloakTestContainer.alternateIssuer(
            URI.create("http://127.0.0.1:32768" + REALM_PATH), false));
  }

  @Test
  void aRemoteDockerHostHasNoAlternate() {
    assertThrows(
        IllegalStateException.class,
        () ->
            KeycloakTestContainer.alternateIssuer(
                URI.create("http://docker.internal:32768" + REALM_PATH), false));
  }

  @Test
  void aNetworkPinnedFixtureHasNoAlternate() {
    assertThrows(
        IllegalStateException.class,
        () ->
            KeycloakTestContainer.alternateIssuer(
                URI.create("http://localhost:32768" + REALM_PATH), true));
  }
}
