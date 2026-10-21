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
package com.forwardmeasure.testcontainers.junit.cassandra;

import com.forwardmeasure.testcontainers.cassandra.CassandraContainerConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Provides a Cassandra container to the annotated JUnit test class: by default one container
 * started for the class and closed after it; with {@link #shared()} the JVM-wide shared container
 * for these settings, which is never closed by the class. With {@link #keyspacePerClass()} the
 * class also gets a fresh keyspace, dropped after the class. Usable as a meta-annotation, and
 * {@code @Nested} classes inherit the settings of the class that encloses them.
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(CassandraContainerExtension.class)
public @interface WithCassandraContainer {

  String image() default CassandraContainerConfiguration.DEFAULT_IMAGE_NAME;

  String maxHeapSize() default CassandraContainerConfiguration.DEFAULT_MAX_HEAP_SIZE;

  String heapNewSize() default CassandraContainerConfiguration.DEFAULT_HEAP_NEW_SIZE;

  long memoryBytes() default CassandraContainerConfiguration.DEFAULT_MEMORY_BYTES;

  long memorySwapBytes() default CassandraContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES;

  boolean passwordAuthentication() default false;

  /** A classpath resource replacing the image's {@code cassandra.yaml}; empty for none. */
  String configurationFile() default "";

  long startupTimeoutSeconds() default
      CassandraContainerConfiguration.DEFAULT_STARTUP_TIMEOUT_SECONDS;

  /**
   * Use the JVM-wide shared container for these settings ({@code CassandraTestContainers.shared})
   * instead of starting one for this class. The class never closes a shared container.
   */
  boolean shared() default false;

  /**
   * Create a unique keyspace (prefixed with the test class's simple name) before the class and drop
   * it after; it is injectable as a {@code CassandraKeyspace} parameter and available from {@link
   * CassandraContainerExtension#keyspaceFor(Class)}.
   */
  boolean keyspacePerClass() default false;
}
