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
package com.forwardmeasure.testcontainers.junit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.testcontainers.junit.kafka.KafkaContainerExtension;
import com.forwardmeasure.testcontainers.junit.kubernetes.KubernetesContainerExtension;
import com.forwardmeasure.testcontainers.junit.opensearch.OpenSearchContainerExtension;
import com.forwardmeasure.testcontainers.junit.postgresql.PostgreSqlContainerExtension;
import com.forwardmeasure.testcontainers.kafka.KafkaTestContainer;
import com.forwardmeasure.testcontainers.kubernetes.KubernetesTestContainer;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainer;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Runs extension lifecycle contracts inside real Jupiter contexts, including default registration.
 */
class ContainerExtensionLifecycleTest {
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Postgres {
    @RegisterExtension
    final Lifecycle<PostgreSqlContainerExtension> fixture =
        new Lifecycle<>(
            new PostgreSqlContainerExtension(),
            value -> ((PostgreSqlTestContainer) value).isRunning());

    @Test
    void injectsOnlySupportedParameters(PostgreSqlTestContainer database, TestInfo test)
        throws Exception {
      assertNotNull(test.getTestMethod().orElseThrow());
      assertSame(database, PostgreSqlContainerExtension.containerFor(getClass()));
      try (var connection = database.dataSource().getConnection()) {
        assertTrue(connection.isValid(2));
      }
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Kafka {
    @RegisterExtension
    final Lifecycle<KafkaContainerExtension> fixture =
        new Lifecycle<>(
            new KafkaContainerExtension(), value -> ((KafkaTestContainer) value).isRunning());

    @Test
    void injectsOnlySupportedParameters(KafkaTestContainer broker, TestInfo test) {
      assertNotNull(test.getTestMethod().orElseThrow());
      assertTrue(broker.isRunning());
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class OpenSearch {
    @RegisterExtension
    final Lifecycle<OpenSearchContainerExtension> fixture =
        new Lifecycle<>(
            new OpenSearchContainerExtension(),
            value -> ((OpenSearchTestContainer) value).isRunning());

    @Test
    void injectsOnlySupportedParameters(OpenSearchTestContainer search, TestInfo test) {
      assertNotNull(test.getTestMethod().orElseThrow());
      assertTrue(search.isRunning());
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Kubernetes {
    @RegisterExtension
    final Lifecycle<KubernetesContainerExtension> fixture =
        new Lifecycle<>(
            new KubernetesContainerExtension(),
            value -> ((KubernetesTestContainer) value).isRunning());

    @Test
    void injectsOnlySupportedParameters(KubernetesTestContainer cluster, TestInfo test) {
      assertNotNull(test.getTestMethod().orElseThrow());
      try (var client = cluster.createClient()) {
        assertFalse(client.nodes().list().getItems().isEmpty());
      }
    }
  }

  static final class Lifecycle<T extends BeforeAllCallback & AfterAllCallback & ParameterResolver>
      implements BeforeAllCallback, AfterAllCallback, ParameterResolver {
    private final T delegate;
    private final Predicate<Object> running;
    private Object container;

    Lifecycle(T delegate, Predicate<Object> running) {
      this.delegate = delegate;
      this.running = running;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
      assertThrows(
          ParameterResolutionException.class, () -> delegate.resolveParameter(null, context));
      if (delegate instanceof PostgreSqlContainerExtension) {
        assertThrows(
            IllegalStateException.class,
            () -> PostgreSqlContainerExtension.containerFor(context.getRequiredTestClass()));
      }
      delegate.beforeAll(context);
      container = delegate.resolveParameter(null, context);
      assertTrue(running.test(container));
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
      delegate.afterAll(context);
      assertFalse(running.test(container), "The extension must close its owned container");
      delegate.afterAll(context);
      assertThrows(
          ParameterResolutionException.class, () -> delegate.resolveParameter(null, context));
      if (delegate instanceof PostgreSqlContainerExtension) {
        assertThrows(
            IllegalStateException.class,
            () -> PostgreSqlContainerExtension.containerFor(context.getRequiredTestClass()));
      }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
      return delegate.supportsParameter(parameter, context);
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
      return delegate.resolveParameter(parameter, context);
    }
  }
}
