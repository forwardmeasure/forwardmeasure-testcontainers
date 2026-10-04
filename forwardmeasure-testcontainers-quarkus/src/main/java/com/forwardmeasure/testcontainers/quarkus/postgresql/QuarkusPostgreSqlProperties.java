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

import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Maps a running PostgreSQL fixture to Quarkus datasource properties. */
public final class QuarkusPostgreSqlProperties {

  private QuarkusPostgreSqlProperties() {}

  public static Map<String, String> from(
      PostgreSqlTestContainer container, String[] datasourceNames, boolean useNetworkJdbcUrl) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(datasourceNames, "datasourceNames");
    String jdbcUrl = useNetworkJdbcUrl ? container.networkJdbcUrl() : container.hostJdbcUrl();

    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("tc.postgresql.containerName", container.containerName());
    properties.put("tc.postgresql.host", container.host());
    properties.put("tc.postgresql.port", Integer.toString(container.mappedPort()));
    addDatasource(properties, "quarkus.datasource", container, jdbcUrl);

    for (String datasourceName : datasourceNames) {
      if (datasourceName == null || datasourceName.isBlank()) {
        continue;
      }
      String prefix = "quarkus.datasource.\"" + datasourceName + "\"";
      addDatasource(properties, prefix, container, jdbcUrl);
    }
    return Map.copyOf(properties);
  }

  private static void addDatasource(
      Map<String, String> properties,
      String prefix,
      PostgreSqlTestContainer container,
      String jdbcUrl) {
    properties.put(prefix + ".db-kind", "postgresql");
    properties.put(prefix + ".username", container.username());
    properties.put(prefix + ".password", container.password());
    properties.put(prefix + ".jdbc.url", jdbcUrl);
  }
}
