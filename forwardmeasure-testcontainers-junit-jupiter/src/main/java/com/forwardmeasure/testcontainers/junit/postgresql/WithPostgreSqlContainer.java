package com.forwardmeasure.testcontainers.junit.postgresql;

import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/** Starts one PostgreSQL container for the annotated JUnit test class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(PostgreSqlContainerExtension.class)
public @interface WithPostgreSqlContainer {

  String image() default "postgres:18-alpine";

  String databaseName() default "forwardmeasure_test";

  String username() default "forwardmeasure";

  String password() default "forwardmeasure-test-only";

  long memoryBytes() default PostgreSqlContainerConfiguration.DEFAULT_MEMORY_BYTES;

  long memorySwapBytes() default PostgreSqlContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES;
}
