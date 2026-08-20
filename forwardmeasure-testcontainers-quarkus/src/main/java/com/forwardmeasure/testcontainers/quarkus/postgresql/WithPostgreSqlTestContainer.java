package com.forwardmeasure.testcontainers.quarkus.postgresql;

import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import io.quarkus.test.common.QuarkusTestResource;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Configures a PostgreSQL Testcontainer as a Quarkus test resource. */
@QuarkusTestResource(PostgreSqlTestResourceLifecycleManager.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WithPostgreSqlTestContainer {

  String image() default "postgres:18-alpine";

  String databaseName() default "forwardmeasure_test";

  String username() default "forwardmeasure";

  String password() default "forwardmeasure-test-only";

  String[] datasourceNames() default {};

  String networkAlias() default "postgres";

  boolean useNetworkJdbcUrl() default false;

  long memoryBytes() default PostgreSqlContainerConfiguration.DEFAULT_MEMORY_BYTES;

  long memorySwapBytes() default PostgreSqlContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES;
}
