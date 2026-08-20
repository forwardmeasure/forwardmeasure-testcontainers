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
