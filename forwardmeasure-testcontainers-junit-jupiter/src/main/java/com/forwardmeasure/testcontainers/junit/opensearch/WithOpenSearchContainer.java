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
package com.forwardmeasure.testcontainers.junit.opensearch;

import com.forwardmeasure.testcontainers.opensearch.OpenSearchContainerConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Provides an OpenSearch container to the annotated JUnit test class: by default one container
 * started for the class and closed after it; with {@link #shared()} the JVM-wide shared container
 * for these settings, which the class never closes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(OpenSearchContainerExtension.class)
public @interface WithOpenSearchContainer {

  String image() default "forwardmeasure/opensearch:3.8.0";

  boolean securityEnabled() default false;

  long memoryBytes() default OpenSearchContainerConfiguration.DEFAULT_MEMORY_BYTES;

  long memorySwapBytes() default OpenSearchContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES;

  /**
   * Use the JVM-wide shared container for these settings ({@code OpenSearchTestContainers.shared})
   * instead of starting one for this class; give each class its own index names.
   */
  boolean shared() default false;
}
