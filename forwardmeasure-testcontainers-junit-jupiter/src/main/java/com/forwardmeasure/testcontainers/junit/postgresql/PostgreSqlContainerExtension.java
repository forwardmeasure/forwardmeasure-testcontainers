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
package com.forwardmeasure.testcontainers.junit.postgresql;

import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.utility.DockerImageName;

/** JUnit Jupiter lifecycle and parameter injection for PostgreSQL containers. */
public final class PostgreSqlContainerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(PostgreSqlContainerExtension.class);

  // Keyed independently of the JUnit ExtensionContext.Store above - Spring's
  // @DynamicPropertySource (a static method Spring's own TestContextBootstrapper calls while
  // building MergedContextConfiguration) and Micronaut's TestPropertyProvider#getProperties()
  // need the running container's connection details before their own application context boots,
  // through APIs that receive no ExtensionContext and therefore cannot use JUnit's own
  // ParameterResolver/Store mechanisms. Both of those framework hooks run during per-test-instance
  // processing, which JUnit Jupiter always runs after every registered extension's beforeAll has
  // completed - so by the time either framework asks, the container for that test class is
  // guaranteed to already be in this map, regardless of @ExtendWith registration order.
  private static final Map<Class<?>, PostgreSqlTestContainer> ACTIVE_CONTAINERS =
      new ConcurrentHashMap<>();

  /**
   * The running container {@link WithPostgreSqlContainer} started for {@code testClass}. See this
   * class's own field javadoc above for why this static, class-keyed lookup exists alongside the
   * {@link ParameterResolver}-based injection: it is the only way for a hosted framework's own
   * static/interface-based config-injection hook (not a JUnit Jupiter extension point itself) to
   * reach a container this extension started.
   */
  public static PostgreSqlTestContainer containerFor(Class<?> testClass) {
    PostgreSqlTestContainer container = ACTIVE_CONTAINERS.get(testClass);
    if (container == null) {
      throw new IllegalStateException(
          "No PostgreSqlTestContainer is running for "
              + testClass.getName()
              + " - is it annotated with @WithPostgreSqlContainer, and has beforeAll run yet?");
    }
    return container;
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    WithPostgreSqlContainer annotation =
        context.getRequiredTestClass().getAnnotation(WithPostgreSqlContainer.class);
    PostgreSqlContainerConfiguration configuration =
        annotation == null
            ? PostgreSqlContainerConfiguration.defaults()
            : configuration(annotation);
    PostgreSqlTestContainer container = new PostgreSqlTestContainer(configuration).start();
    store(context).put(key(context), container);
    ACTIVE_CONTAINERS.put(context.getRequiredTestClass(), container);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    PostgreSqlTestContainer container =
        store(context).remove(key(context), PostgreSqlTestContainer.class);
    ACTIVE_CONTAINERS.remove(context.getRequiredTestClass(), container);
    if (container != null) {
      container.close();
    }
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    return parameterContext.getParameter().getType().equals(PostgreSqlTestContainer.class);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    PostgreSqlTestContainer container =
        store(extensionContext).get(key(extensionContext), PostgreSqlTestContainer.class);
    if (container == null) {
      throw new ParameterResolutionException("PostgreSQL test container is not available");
    }
    return container;
  }

  private PostgreSqlContainerConfiguration configuration(WithPostgreSqlContainer annotation) {
    return new PostgreSqlContainerConfiguration(
        DockerImageName.parse(annotation.image()),
        annotation.databaseName(),
        annotation.username(),
        annotation.password(),
        Optional.empty(),
        List.of(),
        annotation.memoryBytes(),
        annotation.memorySwapBytes());
  }

  private ExtensionContext.Store store(ExtensionContext context) {
    return context.getRoot().getStore(NAMESPACE);
  }

  private Class<?> key(ExtensionContext context) {
    return context.getRequiredTestClass();
  }
}
