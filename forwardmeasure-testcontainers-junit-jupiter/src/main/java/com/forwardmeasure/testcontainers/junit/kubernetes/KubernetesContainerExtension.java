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
package com.forwardmeasure.testcontainers.junit.kubernetes;

import com.forwardmeasure.testcontainers.kubernetes.KubernetesContainerConfiguration;
import com.forwardmeasure.testcontainers.kubernetes.KubernetesTestContainer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.utility.DockerImageName;

/** JUnit Jupiter lifecycle and parameter injection for Kubernetes (K3s) containers. */
public final class KubernetesContainerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(KubernetesContainerExtension.class);

  @Override
  public void beforeAll(ExtensionContext context) {
    WithKubernetesContainer annotation =
        context.getRequiredTestClass().getAnnotation(WithKubernetesContainer.class);
    KubernetesContainerConfiguration configuration =
        annotation == null
            ? KubernetesContainerConfiguration.defaults()
            : new KubernetesContainerConfiguration(DockerImageName.parse(annotation.image()));
    KubernetesTestContainer container = new KubernetesTestContainer(configuration).start();
    store(context).put(key(context), container);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    KubernetesTestContainer container =
        store(context).remove(key(context), KubernetesTestContainer.class);
    if (container != null) {
      container.close();
    }
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    return parameterContext.getParameter().getType().equals(KubernetesTestContainer.class);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    KubernetesTestContainer container =
        store(extensionContext).get(key(extensionContext), KubernetesTestContainer.class);
    if (container == null) {
      throw new ParameterResolutionException("Kubernetes test container is not available");
    }
    return container;
  }

  private ExtensionContext.Store store(ExtensionContext context) {
    return context.getRoot().getStore(NAMESPACE);
  }

  private Class<?> key(ExtensionContext context) {
    return context.getRequiredTestClass();
  }
}
