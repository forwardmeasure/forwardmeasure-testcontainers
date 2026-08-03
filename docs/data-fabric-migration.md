# Data Fabric Migration

Data Fabric remains unchanged until these independently versioned artifacts are published. Its PostgreSQL test support then migrates as follows:

1. Add `forwardmeasure-testcontainers-quarkus` as a test dependency managed from the Data Fabric parent.
2. Replace `WithPostgreSQLTestContainer` with `WithPostgreSqlTestContainer` from the shared Quarkus adapter.
3. Remove `PostgreSQLTestContainerConfigurator` and `PostgreSQLTestContainerLifecycleManager` after all consumers compile.
4. Retain application-specific test bootstrap beside each test; schema creation and application migrations are not Quarkus adapter responsibilities.
5. Run the complete Data Fabric reactor before removing the old PostgreSQL Testcontainers dependencies.

The new lifecycle intentionally changes several unsafe behaviours:

- credentials are returned to Quarkus but never logged;
- the lifecycle manager retains and closes the container it starts;
- a Dev Services network ID is attached as a caller-owned Testcontainers
  network, preserving Docker DNS aliases;
- network JDBC URLs are constructed from an explicit alias and fail closed when no Dev Services network exists;
- no unrelated Docker network is created by the adapter; and
- default and named datasource properties are produced by one deterministic mapper.

Kafka, MinIO, OpenSearch, Keycloak, Spark and other Data Fabric fixtures are separate future provider extractions. They must not be pulled transitively into PostgreSQL-only tests.
