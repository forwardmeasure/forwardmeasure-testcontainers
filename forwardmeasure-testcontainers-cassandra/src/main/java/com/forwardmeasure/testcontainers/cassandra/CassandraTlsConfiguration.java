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

import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.internal.core.ssl.DefaultSslEngineFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * File-backed TLS material for a real Cassandra listener and the fixture's readiness/admin client.
 */
public record CassandraTlsConfiguration(
    Store serverKeyStore,
    Store serverTrustStore,
    Store clientTrustStore,
    Optional<Store> clientKeyStore,
    boolean requireClientAuthentication,
    boolean hostnameValidation) {
  public CassandraTlsConfiguration {
    Objects.requireNonNull(serverKeyStore);
    Objects.requireNonNull(serverTrustStore);
    Objects.requireNonNull(clientTrustStore);
    clientKeyStore = Objects.requireNonNull(clientKeyStore);
    if (requireClientAuthentication && clientKeyStore.isEmpty()) {
      throw new IllegalArgumentException(
          "The readiness client requires a certificate for mutual TLS");
    }
  }

  /** Adds the real driver's TLS policy, usable by independent positive/negative client sessions. */
  public void configureClient(
      ProgrammaticDriverConfigLoaderBuilder builder, boolean includeClientIdentity) {
    builder
        .withClass(DefaultDriverOption.SSL_ENGINE_FACTORY_CLASS, DefaultSslEngineFactory.class)
        .withString(DefaultDriverOption.SSL_TRUSTSTORE_PATH, clientTrustStore.file().toString())
        .withString(DefaultDriverOption.SSL_TRUSTSTORE_PASSWORD, clientTrustStore.password())
        .withBoolean(DefaultDriverOption.SSL_HOSTNAME_VALIDATION, hostnameValidation);
    if (includeClientIdentity) {
      clientKeyStore.ifPresent(
          store ->
              builder
                  .withString(DefaultDriverOption.SSL_KEYSTORE_PATH, store.file().toString())
                  .withString(DefaultDriverOption.SSL_KEYSTORE_PASSWORD, store.password()));
    }
  }

  /** Passwords never appear in configuration diagnostics. */
  public record Store(Path file, String password) {
    public Store {
      file = Objects.requireNonNull(file).toAbsolutePath().normalize();
      Objects.requireNonNull(password);
      if (!Files.isRegularFile(file)) {
        throw new IllegalArgumentException("TLS store must be a regular file");
      }
    }

    @Override
    public String toString() {
      return "Store[file=" + file + ", password=<redacted>]";
    }
  }
}
