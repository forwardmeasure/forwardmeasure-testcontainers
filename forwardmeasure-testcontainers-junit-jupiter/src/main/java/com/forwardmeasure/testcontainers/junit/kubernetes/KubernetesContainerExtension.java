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
