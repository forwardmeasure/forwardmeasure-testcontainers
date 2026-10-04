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
package com.forwardmeasure.testcontainers.junit.kafka;

import com.forwardmeasure.testcontainers.kafka.KafkaContainerConfiguration;
import com.forwardmeasure.testcontainers.kafka.KafkaTestContainer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.utility.DockerImageName;

/** JUnit Jupiter lifecycle and parameter injection for Kafka containers. */
public final class KafkaContainerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(KafkaContainerExtension.class);

  @Override
  public void beforeAll(ExtensionContext context) {
    WithKafkaContainer annotation =
        context.getRequiredTestClass().getAnnotation(WithKafkaContainer.class);
    KafkaContainerConfiguration configuration =
        annotation == null ? KafkaContainerConfiguration.defaults() : configuration(annotation);
    KafkaTestContainer container = new KafkaTestContainer(configuration).start();
    store(context).put(key(context), container);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    KafkaTestContainer container = store(context).remove(key(context), KafkaTestContainer.class);
    if (container != null) {
      container.close();
    }
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    return parameterContext.getParameter().getType().equals(KafkaTestContainer.class);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    KafkaTestContainer container =
        store(extensionContext).get(key(extensionContext), KafkaTestContainer.class);
    if (container == null) {
      throw new ParameterResolutionException("Kafka test container is not available");
    }
    return container;
  }

  private KafkaContainerConfiguration configuration(WithKafkaContainer annotation) {
    KafkaContainerConfiguration configuration =
        new KafkaContainerConfiguration(
            DockerImageName.parse(annotation.image()), Optional.empty(), List.of());
    return annotation.hostDockerInternalListener()
        ? configuration.withHostDockerInternalListener()
        : configuration;
  }

  private ExtensionContext.Store store(ExtensionContext context) {
    return context.getRoot().getStore(NAMESPACE);
  }

  private Class<?> key(ExtensionContext context) {
    return context.getRequiredTestClass();
  }
}
