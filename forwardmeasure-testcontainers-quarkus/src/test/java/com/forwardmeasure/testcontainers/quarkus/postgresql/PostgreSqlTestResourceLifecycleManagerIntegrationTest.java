package com.forwardmeasure.testcontainers.quarkus.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

@WithPostgreSqlTestContainer(
        databaseName = "quarkus_contract",
        datasourceNames = {"audit"})
class PostgreSqlTestResourceLifecycleManagerIntegrationTest {

    @Test
    void startsRealPostgresAndProducesDefaultAndNamedDatasourceProperties()
            throws Exception {
        WithPostgreSqlTestContainer configuration = getClass()
                .getAnnotation(WithPostgreSqlTestContainer.class);
        PostgreSqlTestResourceLifecycleManager manager =
                new PostgreSqlTestResourceLifecycleManager();
        manager.init(configuration);

        Map<String, String> properties = manager.start();
        try {
            assertEquals(
                    properties.get("quarkus.datasource.jdbc.url"),
                    properties.get("quarkus.datasource.\"audit\".jdbc.url"));
            assertEquals(
                    "forwardmeasure",
                    properties.get("quarkus.datasource.\"audit\".username"));

            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(properties.get("quarkus.datasource.jdbc.url"));
            dataSource.setUser(properties.get("quarkus.datasource.username"));
            dataSource.setPassword(properties.get("quarkus.datasource.password"));
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("select current_database()")) {
                assertTrue(result.next());
                assertEquals("quarkus_contract", result.getString(1));
            }
        } finally {
            manager.stop();
        }
    }
}
