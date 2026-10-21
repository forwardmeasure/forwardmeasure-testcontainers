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
package com.forwardmeasure.testcontainers.quarkus.cassandra;

import com.forwardmeasure.testcontainers.cassandra.CassandraContainerConfiguration;
import io.quarkus.test.common.QuarkusTestResource;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures a Cassandra Testcontainer as a Quarkus test resource.
 *
 * <p>Applications read Cassandra settings under their own keys, so the names of the configuration
 * properties to emit are attributes too. A blank property name means "do not emit"; {@link
 * #contactPointsProperty()} is required. Exactly the named properties are emitted, nothing else.
 */
@QuarkusTestResource(CassandraTestResourceLifecycleManager.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WithCassandraTestContainer {

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

  /** Receives {@code host:port}: the mapped host port, or the network alias and 9042. */
  String contactPointsProperty() default "quarkus.cassandra.contact-points";

  String localDatacenterProperty() default "quarkus.cassandra.local-datacenter";

  /** Emitted only when {@link #passwordAuthentication()} is on. */
  String usernameProperty() default "quarkus.cassandra.auth.username";

  /** Emitted only when {@link #passwordAuthentication()} is on. */
  String passwordProperty() default "quarkus.cassandra.auth.password";

  /**
   * When set, a unique keyspace is created before the application starts, its name is emitted under
   * this property, and it is dropped when the resource stops. Empty: no keyspace.
   */
  String keyspaceProperty() default "";

  /** The prefix of the created keyspace's name, normalised as the fixture documents. */
  String keyspacePrefix() default "quarkus_test";

  /** The container's alias on a Quarkus Dev Services network, when there is one. */
  String networkAlias() default "cassandra";

  /**
   * Emit {@code networkAlias:9042} instead of the host address, for an application running in a
   * container on the Dev Services network. Fails closed when there is no such network.
   */
  boolean useNetworkContactPoint() default false;

  /**
   * Use the JVM-wide shared container for these settings instead of starting one; stopping the
   * resource then drops only its keyspace and leaves the container running.
   */
  boolean shared() default false;
}
