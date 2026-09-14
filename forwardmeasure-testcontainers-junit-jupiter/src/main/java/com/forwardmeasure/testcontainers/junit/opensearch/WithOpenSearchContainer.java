package com.forwardmeasure.testcontainers.junit.opensearch;

import com.forwardmeasure.testcontainers.opensearch.OpenSearchContainerConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/** Starts one OpenSearch container for the annotated JUnit test class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(OpenSearchContainerExtension.class)
public @interface WithOpenSearchContainer {

  String image() default "forwardmeasure/opensearch:3.8.0";

  boolean securityEnabled() default false;

  long memoryBytes() default OpenSearchContainerConfiguration.DEFAULT_MEMORY_BYTES;

  long memorySwapBytes() default OpenSearchContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES;
}
