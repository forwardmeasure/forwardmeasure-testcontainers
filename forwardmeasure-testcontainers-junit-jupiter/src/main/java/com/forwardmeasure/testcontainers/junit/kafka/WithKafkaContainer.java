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
package com.forwardmeasure.testcontainers.junit.kafka;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/** Starts one Kafka container for the annotated JUnit test class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(KafkaContainerExtension.class)
public @interface WithKafkaContainer {

  String image() default "apache/kafka:4.3.1";

  /**
   * See {@code KafkaContainerConfiguration#withHostDockerInternalListener}'s own javadoc - needed
   * only by a test whose real consumer (e.g. a K3s pod via {@code pod.spec.hostAliases}) can
   * resolve {@code host.docker.internal} but not "localhost", which is what every other listener
   * advertises by default.
   */
  boolean hostDockerInternalListener() default false;
}
