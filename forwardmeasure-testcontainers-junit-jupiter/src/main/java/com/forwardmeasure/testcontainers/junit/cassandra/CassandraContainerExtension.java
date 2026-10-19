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
package com.forwardmeasure.testcontainers.junit.cassandra;

import com.forwardmeasure.testcontainers.cassandra.CassandraContainerConfiguration;
import com.forwardmeasure.testcontainers.cassandra.CassandraKeyspace;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainer;
import com.forwardmeasure.testcontainers.cassandra.CassandraTestContainers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.testcontainers.utility.DockerImageName;

/**
 * JUnit Jupiter lifecycle and parameter injection for Cassandra containers and per-class keyspaces.
 *
 * <p>Registered by {@link WithCassandraContainer}, or programmatically with {@link
 * #CassandraContainerExtension(CassandraContainerConfiguration, boolean, boolean)} on a static
 * {@code @RegisterExtension} field. Injects {@link CassandraTestContainer} and, when a keyspace is
 * created per class, {@link CassandraKeyspace} parameters.
 */
public final class CassandraContainerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(CassandraContainerExtension.class);

  // Keyed independently of the JUnit ExtensionContext.Store - Spring's @DynamicPropertySource (a
  // static method Spring's own TestContextBootstrapper calls while building
  // MergedContextConfiguration) and Micronaut's TestPropertyProvider#getProperties() need the
  // running container's contact point and keyspace before their own application context boots,
  // through APIs that receive no ExtensionContext and therefore cannot use JUnit's own
  // ParameterResolver/Store mechanisms. Both of those framework hooks run during per-test-instance
  // processing, which JUnit Jupiter always runs after every registered extension's beforeAll has
  // completed - so by the time either framework asks, the container (and keyspace) for that test
  // class is guaranteed to already be in this map, regardless of @ExtendWith registration order.
  private static final Map<Class<?>, Active> ACTIVE = new ConcurrentHashMap<>();

  private final Settings programmatic;

  /** Reads its settings from {@link WithCassandraContainer}, or uses the defaults without one. */
  public CassandraContainerExtension() {
    this.programmatic = null;
  }

  /** Explicit settings, for {@code @RegisterExtension} registration without the annotation. */
  public CassandraContainerExtension(
      CassandraContainerConfiguration configuration, boolean shared, boolean keyspacePerClass) {
    this.programmatic =
        new Settings(
            Objects.requireNonNull(configuration, "configuration"), shared, keyspacePerClass);
  }

  /**
   * The running container this extension provides to {@code testClass}. See the field comment on
   * this class for why this static, class-keyed lookup exists alongside {@link ParameterResolver}
   * injection: it is the only way for a hosted framework's own static or interface-based
   * configuration hook (not itself a JUnit Jupiter extension point) to reach the container.
   */
  public static CassandraTestContainer containerFor(Class<?> testClass) {
    return active(testClass).container();
  }

  /** The keyspace created for {@code testClass}; see {@link #containerFor(Class)}. */
  public static CassandraKeyspace keyspaceFor(Class<?> testClass) {
    return active(testClass)
        .keyspace()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No keyspace was created for "
                        + testClass.getName()
                        + " - set keyspacePerClass = true on @WithCassandraContainer"));
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    Class<?> testClass = context.getRequiredTestClass();
    Settings settings = programmatic != null ? programmatic : settings(testClass);
    CassandraTestContainer container =
        settings.shared()
            ? CassandraTestContainers.shared(settings.configuration())
            : new CassandraTestContainer(settings.configuration()).start();
    Optional<CassandraKeyspace> keyspace = Optional.empty();
    if (settings.keyspacePerClass()) {
      try {
        keyspace = Optional.of(container.createUniqueKeyspace(keyspacePrefix(testClass)));
      } catch (RuntimeException | Error failure) {
        container.close(); // a no-op for a shared container
        throw failure;
      }
    }
    Active active = new Active(container, keyspace);
    store(context).put(testClass, active);
    ACTIVE.put(testClass, active);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    Class<?> testClass = context.getRequiredTestClass();
    Active active = store(context).remove(testClass, Active.class);
    if (active == null) {
      return;
    }
    ACTIVE.remove(testClass, active);
    try {
      active.keyspace().ifPresent(keyspace -> active.container().dropKeyspace(keyspace.name()));
    } finally {
      if (!active.container().isShared()) {
        active.container().close();
      }
    }
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    Class<?> type = parameterContext.getParameter().getType();
    return type.equals(CassandraTestContainer.class) || type.equals(CassandraKeyspace.class);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    Active active = store(extensionContext).get(key(extensionContext), Active.class);
    if (active == null) {
      throw new ParameterResolutionException("Cassandra test container is not available");
    }
    if (parameterContext.getParameter().getType().equals(CassandraKeyspace.class)) {
      return active
          .keyspace()
          .orElseThrow(
              () ->
                  new ParameterResolutionException(
                      "No keyspace was created - set keyspacePerClass = true"));
    }
    return active.container();
  }

  private static Active active(Class<?> testClass) {
    Active active = ACTIVE.get(testClass);
    if (active == null) {
      throw new IllegalStateException(
          "No CassandraTestContainer is running for "
              + testClass.getName()
              + " - is it annotated with @WithCassandraContainer, and has beforeAll run yet?");
    }
    return active;
  }

  private static Settings settings(Class<?> testClass) {
    for (Class<?> candidate = testClass;
        candidate != null;
        candidate = candidate.getEnclosingClass()) {
      Optional<WithCassandraContainer> annotation =
          AnnotationSupport.findAnnotation(candidate, WithCassandraContainer.class);
      if (annotation.isPresent()) {
        return settings(annotation.get());
      }
    }
    return new Settings(CassandraContainerConfiguration.defaults(), false, false);
  }

  private static Settings settings(WithCassandraContainer annotation) {
    return new Settings(
        new CassandraContainerConfiguration(
            DockerImageName.parse(annotation.image()),
            annotation.maxHeapSize(),
            annotation.heapNewSize(),
            annotation.memoryBytes(),
            annotation.memorySwapBytes(),
            annotation.passwordAuthentication(),
            annotation.configurationFile().isBlank()
                ? Optional.empty()
                : Optional.of(annotation.configurationFile()),
            Duration.ofSeconds(annotation.startupTimeoutSeconds()),
            Optional.empty(),
            List.of()),
        annotation.shared(),
        annotation.keyspacePerClass());
  }

  private static String keyspacePrefix(Class<?> testClass) {
    String simpleName = testClass.getSimpleName();
    return simpleName.isBlank() ? "test" : simpleName;
  }

  private ExtensionContext.Store store(ExtensionContext context) {
    return context.getRoot().getStore(NAMESPACE);
  }

  private Class<?> key(ExtensionContext context) {
    return context.getRequiredTestClass();
  }

  private record Settings(
      CassandraContainerConfiguration configuration, boolean shared, boolean keyspacePerClass) {}

  private record Active(CassandraTestContainer container, Optional<CassandraKeyspace> keyspace) {}
}
