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
