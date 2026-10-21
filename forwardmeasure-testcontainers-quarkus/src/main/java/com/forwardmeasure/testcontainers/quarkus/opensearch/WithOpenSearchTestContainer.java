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
package com.forwardmeasure.testcontainers.quarkus.opensearch;

import com.forwardmeasure.testcontainers.opensearch.OpenSearchContainerConfiguration;
import io.quarkus.test.common.QuarkusTestResource;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures an OpenSearch Testcontainer as a Quarkus test resource.
 *
 * <p>The names of the configuration properties to emit are attributes, because applications read
 * OpenSearch settings under their own keys. A blank property name means "do not emit"; {@link
 * #hostsProperty()} is required. Exactly the named properties are emitted, nothing else.
 */
@QuarkusTestResource(OpenSearchTestResourceLifecycleManager.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WithOpenSearchTestContainer {

  String image() default "forwardmeasure/opensearch:3.8.0";

  /** The security plugin on: basic authentication over https with the image's demo certificates. */
  boolean securityEnabled() default false;

  long memoryBytes() default OpenSearchContainerConfiguration.DEFAULT_MEMORY_BYTES;

  long memorySwapBytes() default OpenSearchContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES;

  /** Receives {@code host:port}: the mapped host port, or the network alias and 9200. */
  String hostsProperty() default "quarkus.opensearch.hosts";

  /** Receives {@code http}, or {@code https} when security is enabled. */
  String protocolProperty() default "quarkus.opensearch.protocol";

  /** Emitted only when {@link #securityEnabled()} is on. */
  String usernameProperty() default "quarkus.opensearch.username";

  /** Emitted only when {@link #securityEnabled()} is on. */
  String passwordProperty() default "quarkus.opensearch.password";

  /** The container's alias on a Quarkus Dev Services network, when there is one. */
  String networkAlias() default "opensearch";

  /**
   * Emit {@code networkAlias:9200} instead of the host address, for an application running in a
   * container on the Dev Services network. Fails closed when there is no such network.
   */
  boolean useNetworkEndpoint() default false;

  /** Use the JVM-wide shared container for these settings; stopping the resource leaves it up. */
  boolean shared() default false;
}
