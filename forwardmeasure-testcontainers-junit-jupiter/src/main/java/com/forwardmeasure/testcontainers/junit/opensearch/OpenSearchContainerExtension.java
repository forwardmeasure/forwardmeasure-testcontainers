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
package com.forwardmeasure.testcontainers.junit.opensearch;

import com.forwardmeasure.testcontainers.opensearch.OpenSearchContainerConfiguration;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainer;
import com.forwardmeasure.testcontainers.opensearch.OpenSearchTestContainers;
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
import org.testcontainers.utility.DockerImageName;

/**
 * JUnit Jupiter lifecycle and parameter injection for OpenSearch containers.
 *
 * <p>Registered by {@link WithOpenSearchContainer}, or programmatically with {@link
 * #OpenSearchContainerExtension(OpenSearchContainerConfiguration, boolean)} on a static
 * {@code @RegisterExtension} field.
 */
public final class OpenSearchContainerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(OpenSearchContainerExtension.class);

  // Keyed independently of the JUnit ExtensionContext.Store above - Spring's
  // @DynamicPropertySource (a static method Spring's own TestContextBootstrapper calls while
  // building MergedContextConfiguration) and Micronaut's TestPropertyProvider#getProperties()
  // need the running container's endpoint before their own application context boots, through
  // APIs that receive no ExtensionContext and therefore cannot use JUnit's own
  // ParameterResolver/Store mechanisms. Both of those framework hooks run during per-test-instance
  // processing, which JUnit Jupiter always runs after every registered extension's beforeAll has
  // completed - so by the time either framework asks, the container for that test class is
  // guaranteed to already be in this map, regardless of @ExtendWith registration order.
  private static final Map<Class<?>, OpenSearchTestContainer> ACTIVE_CONTAINERS =
      new ConcurrentHashMap<>();

  private final OpenSearchContainerConfiguration programmaticConfiguration;
  private final boolean programmaticShared;

  /** Reads its settings from {@link WithOpenSearchContainer}, or uses the defaults without one. */
  public OpenSearchContainerExtension() {
    this.programmaticConfiguration = null;
    this.programmaticShared = false;
  }

  /** Explicit settings, for {@code @RegisterExtension} registration without the annotation. */
  public OpenSearchContainerExtension(
      OpenSearchContainerConfiguration configuration, boolean shared) {
    this.programmaticConfiguration = Objects.requireNonNull(configuration, "configuration");
    this.programmaticShared = shared;
  }

  /**
   * The running container this extension provides to {@code testClass}. See this class's field
   * comment above for why this static, class-keyed lookup exists alongside the {@link
   * ParameterResolver}-based injection: it is the only way for a hosted framework's own
   * static/interface-based config-injection hook (not a JUnit Jupiter extension point itself) to
   * reach a container this extension provides.
   */
  public static OpenSearchTestContainer containerFor(Class<?> testClass) {
    OpenSearchTestContainer container = ACTIVE_CONTAINERS.get(testClass);
    if (container == null) {
      throw new IllegalStateException(
          "No OpenSearchTestContainer is running for "
              + testClass.getName()
              + " - is it annotated with @WithOpenSearchContainer, and has beforeAll run yet?");
    }
    return container;
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    OpenSearchContainerConfiguration configuration;
    boolean shared;
    if (programmaticConfiguration != null) {
      configuration = programmaticConfiguration;
      shared = programmaticShared;
    } else {
      WithOpenSearchContainer annotation =
          context.getRequiredTestClass().getAnnotation(WithOpenSearchContainer.class);
      configuration =
          annotation == null
              ? OpenSearchContainerConfiguration.defaults()
              : configuration(annotation);
      shared = annotation != null && annotation.shared();
    }
    OpenSearchTestContainer container =
        shared
            ? OpenSearchTestContainers.shared(configuration)
            : new OpenSearchTestContainer(configuration).start();
    store(context).put(key(context), container);
    ACTIVE_CONTAINERS.put(context.getRequiredTestClass(), container);
  }

  /** Closes the class's own container; a shared container is left running for other classes. */
  @Override
  public void afterAll(ExtensionContext context) {
    OpenSearchTestContainer container =
        store(context).remove(key(context), OpenSearchTestContainer.class);
    ACTIVE_CONTAINERS.remove(context.getRequiredTestClass(), container);
    if (container != null && !container.isShared()) {
      container.close();
    }
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    return parameterContext.getParameter().getType().equals(OpenSearchTestContainer.class);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    OpenSearchTestContainer container =
        store(extensionContext).get(key(extensionContext), OpenSearchTestContainer.class);
    if (container == null) {
      throw new ParameterResolutionException("OpenSearch test container is not available");
    }
    return container;
  }

  private OpenSearchContainerConfiguration configuration(WithOpenSearchContainer annotation) {
    return new OpenSearchContainerConfiguration(
        DockerImageName.parse(annotation.image()),
        annotation.securityEnabled(),
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
