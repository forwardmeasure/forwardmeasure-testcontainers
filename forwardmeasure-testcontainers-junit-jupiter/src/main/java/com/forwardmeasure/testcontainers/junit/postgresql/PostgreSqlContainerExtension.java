package com.forwardmeasure.testcontainers.junit.postgresql;

import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.util.List;
import java.util.Optional;
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

    @Override
    public void beforeAll(ExtensionContext context) {
        WithPostgreSqlContainer annotation = context.getRequiredTestClass()
                .getAnnotation(WithPostgreSqlContainer.class);
        PostgreSqlContainerConfiguration configuration = annotation == null
                ? PostgreSqlContainerConfiguration.defaults()
                : configuration(annotation);
        store(context).put(
                key(context), new PostgreSqlTestContainer(configuration).start());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        PostgreSqlTestContainer container = store(context).remove(
                key(context), PostgreSqlTestContainer.class);
        if (container != null) {
            container.close();
        }
    }

    @Override
    public boolean supportsParameter(
            ParameterContext parameterContext,
            ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType()
                .equals(PostgreSqlTestContainer.class);
    }

    @Override
    public Object resolveParameter(
            ParameterContext parameterContext,
            ExtensionContext extensionContext)
            throws ParameterResolutionException {
        PostgreSqlTestContainer container = store(extensionContext).get(
                key(extensionContext), PostgreSqlTestContainer.class);
        if (container == null) {
            throw new ParameterResolutionException(
                    "PostgreSQL test container is not available");
        }
        return container;
    }

    private PostgreSqlContainerConfiguration configuration(
            WithPostgreSqlContainer annotation) {
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

