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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastax.oss.driver.api.core.CqlSession;
import com.forwardmeasure.testcontainers.cassandra.CassandraContainerConfiguration;
import com.forwardmeasure.testcontainers.cassandra.CassandraKeyspace;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainer;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainers;
import com.forwardmeasure.testcontainers.junit.cassandra.CassandraContainerExtension;
import com.forwardmeasure.testcontainers.junit.kafka.KafkaContainerExtension;
import com.forwardmeasure.testcontainers.junit.kubernetes.KubernetesContainerExtension;
import com.forwardmeasure.testcontainers.junit.opensearch.OpenSearchContainerExtension;
import com.forwardmeasure.testcontainers.junit.postgresql.PostgreSqlContainerExtension;
import com.forwardmeasure.testcontainers.kafka.KafkaTestContainer;
import com.forwardmeasure.testcontainers.kubernetes.KubernetesTestContainer;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchContainerConfiguration;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainer;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainers;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.util.Optional;
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
      assertSame(search, OpenSearchContainerExtension.containerFor(getClass()));
      assertFalse(search.isShared());
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class SharedOpenSearch {
    @RegisterExtension
    final Lifecycle<OpenSearchContainerExtension> fixture =
        new Lifecycle<>(
            new OpenSearchContainerExtension(OpenSearchContainerConfiguration.defaults(), true),
            value -> ((OpenSearchTestContainer) value).isRunning(),
            false);

    @Test
    void injectsTheJvmWideSharedContainer(OpenSearchTestContainer search) {
      assertTrue(search.isShared());
      assertSame(search, OpenSearchContainerExtension.containerFor(getClass()));
      assertSame(
          OpenSearchTestContainers.shared(OpenSearchContainerConfiguration.defaults()), search);
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class SharedCassandraWithClassKeyspace {
    @RegisterExtension
    final CassandraLifecycle fixture =
        new CassandraLifecycle(
            new CassandraContainerExtension(CassandraContainerConfiguration.defaults(), true, true),
            true,
            true);

    @Test
    void injectsTheSharedContainerAndTheClassKeyspace(
        CassandraTestContainer cassandra, CassandraKeyspace keyspace) {
      assertTrue(cassandra.isShared());
      assertSame(cassandra, CassandraContainerExtension.containerFor(getClass()));
      assertEquals(keyspace, CassandraContainerExtension.keyspaceFor(getClass()));
      assertTrue(keyspace.name().startsWith("sharedcassandrawithclasskeyspace_"));
      assertTrue(fixture.keyspaceExists(keyspace.name()));
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class OwnedCassandra {
    @RegisterExtension
    final CassandraLifecycle fixture =
        new CassandraLifecycle(new CassandraContainerExtension(), false, false);

    @Test
    void injectsAnOwnedContainerWithoutAKeyspace(CassandraTestContainer cassandra) {
      assertFalse(cassandra.isShared());
      assertSame(cassandra, CassandraContainerExtension.containerFor(getClass()));
      // keyspacePerClass is off: asking for the keyspace is a clear configuration error.
      assertThrows(
          IllegalStateException.class, () -> CassandraContainerExtension.keyspaceFor(getClass()));
    }
  }

  /**
   * Drives the Cassandra extension's callbacks and checks what each must leave behind: a running
   * container and keyspace before the class; after it, the keyspace dropped, an owned container
   * stopped, a shared one still running, and both lookups cleared.
   */
  static final class CassandraLifecycle
      implements BeforeAllCallback, AfterAllCallback, ParameterResolver {
    private final CassandraContainerExtension delegate;
    private final boolean shared;
    private final boolean keyspacePerClass;
    private CassandraTestContainer container;
    private Optional<CassandraKeyspace> keyspace = Optional.empty();

    CassandraLifecycle(
        CassandraContainerExtension delegate, boolean shared, boolean keyspacePerClass) {
      this.delegate = delegate;
      this.shared = shared;
      this.keyspacePerClass = keyspacePerClass;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
      Class<?> testClass = context.getRequiredTestClass();
      assertThrows(
          IllegalStateException.class, () -> CassandraContainerExtension.containerFor(testClass));
      delegate.beforeAll(context);
      container = CassandraContainerExtension.containerFor(testClass);
      assertTrue(container.isRunning());
      assertEquals(shared, container.isShared());
      if (shared) {
        assertSame(
            CassandraTestContainers.shared(CassandraContainerConfiguration.defaults()), container);
      }
      if (keyspacePerClass) {
        keyspace = Optional.of(CassandraContainerExtension.keyspaceFor(testClass));
        assertTrue(keyspaceExists(keyspace.get().name()));
      }
    }

    @Override
    public void afterAll(ExtensionContext context) {
      Class<?> testClass = context.getRequiredTestClass();
      delegate.afterAll(context);
      if (shared) {
        assertTrue(container.isRunning(), "A shared container must survive the class");
        keyspace.ifPresent(
            created ->
                assertFalse(keyspaceExists(created.name()), "The class keyspace must be dropped"));
      } else {
        assertFalse(container.isRunning(), "The extension must close its owned container");
      }
      delegate.afterAll(context);
      assertThrows(
          IllegalStateException.class, () -> CassandraContainerExtension.containerFor(testClass));
      assertThrows(
          IllegalStateException.class, () -> CassandraContainerExtension.keyspaceFor(testClass));
    }

    boolean keyspaceExists(String name) {
      try (var session =
          CqlSession.builder()
              .addContactPoint(container.contactPoint())
              .withLocalDatacenter(container.localDatacenter())
              .build()) {
        return session
                .execute(
                    "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?",
                    name)
                .one()
            != null;
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
    private final boolean owned;
    private Object container;

    Lifecycle(T delegate, Predicate<Object> running) {
      this(delegate, running, true);
    }

    Lifecycle(T delegate, Predicate<Object> running, boolean owned) {
      this.delegate = delegate;
      this.running = running;
      this.owned = owned;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
      assertThrows(
          ParameterResolutionException.class, () -> delegate.resolveParameter(null, context));
      assertNoLookup(context);
      delegate.beforeAll(context);
      container = delegate.resolveParameter(null, context);
      assertTrue(running.test(container));
      if (delegate instanceof OpenSearchContainerExtension) {
        assertSame(
            container, OpenSearchContainerExtension.containerFor(context.getRequiredTestClass()));
      }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
      delegate.afterAll(context);
      if (owned) {
        assertFalse(running.test(container), "The extension must close its owned container");
      } else {
        assertTrue(running.test(container), "The extension must not close a shared container");
      }
      delegate.afterAll(context);
      assertThrows(
          ParameterResolutionException.class, () -> delegate.resolveParameter(null, context));
      assertNoLookup(context);
    }

    private void assertNoLookup(ExtensionContext context) {
      if (delegate instanceof PostgreSqlContainerExtension) {
        assertThrows(
            IllegalStateException.class,
            () -> PostgreSqlContainerExtension.containerFor(context.getRequiredTestClass()));
      }
      if (delegate instanceof OpenSearchContainerExtension) {
        assertThrows(
            IllegalStateException.class,
            () -> OpenSearchContainerExtension.containerFor(context.getRequiredTestClass()));
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
