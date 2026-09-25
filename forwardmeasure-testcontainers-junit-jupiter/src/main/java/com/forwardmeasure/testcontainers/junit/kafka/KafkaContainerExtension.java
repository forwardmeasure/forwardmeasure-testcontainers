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
