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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.utility.DockerImageName;

/** JUnit Jupiter lifecycle and parameter injection for OpenSearch containers. */
public final class OpenSearchContainerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(OpenSearchContainerExtension.class);

  @Override
  public void beforeAll(ExtensionContext context) {
    WithOpenSearchContainer annotation =
        context.getRequiredTestClass().getAnnotation(WithOpenSearchContainer.class);
    OpenSearchContainerConfiguration configuration =
        annotation == null
            ? OpenSearchContainerConfiguration.defaults()
            : configuration(annotation);
    OpenSearchTestContainer container = new OpenSearchTestContainer(configuration).start();
    store(context).put(key(context), container);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    OpenSearchTestContainer container =
        store(context).remove(key(context), OpenSearchTestContainer.class);
    if (container != null) {
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
