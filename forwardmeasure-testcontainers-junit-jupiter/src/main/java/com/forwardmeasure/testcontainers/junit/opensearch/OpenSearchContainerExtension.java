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
