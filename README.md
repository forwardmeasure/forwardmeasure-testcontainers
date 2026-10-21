# ForwardMeasure Testcontainers

Reusable, framework-neutral Testcontainers fixtures with small framework-specific lifecycle adapters.

The PostgreSQL foundation is split into three responsibilities:

- `forwardmeasure-testcontainers-postgresql` owns configuration and explicit container lifecycle.
- `forwardmeasure-testcontainers-junit-jupiter` owns per-test-class JUnit lifecycle and parameter injection.
- `forwardmeasure-testcontainers-quarkus` maps the same running fixture into Quarkus default and named datasource properties.

Fixtures never log credentials. Consumers can use the core lifecycle without JUnit or Quarkus, and every owner must close what it starts.

See [the architecture](docs/architecture.md).

## Cassandra and OpenSearch for large suites

`forwardmeasure-testcontainers-cassandra` configures Cassandra through an
immutable `CassandraContainerConfiguration` (image, heap, memory, password
authentication, a caller-supplied `cassandra.yaml`, startup timeout, network)
and creates and drops unique keyspaces through the Cassandra driver.
`CassandraTestContainers.shared(configuration)` and
`OpenSearchTestContainers.shared(configuration)` start one container per
configuration per JVM; a shared container ignores `close()` and is stopped by a
shutdown hook. One shared Cassandra with a fresh keyspace per test class keeps
classes isolated without paying a container start for each:

```java
@WithCassandraContainer(shared = true, keyspacePerClass = true)
class GraphStoreTest {
  @Test
  void writes(CassandraTestContainer cassandra, CassandraKeyspace keyspace) { ... }
}
```

`CassandraContainerExtension.containerFor(Class)` / `keyspaceFor(Class)` and
`OpenSearchContainerExtension.containerFor(Class)` serve Spring and Micronaut
property hooks. In Quarkus, `@WithCassandraTestContainer` and
`@WithOpenSearchTestContainer` emit exactly the configuration properties they
name, for example
`@WithCassandraTestContainer(shared = true, keyspaceProperty = "app.keyspace")`.

The staged replacement for Data Fabric's existing Quarkus utility is documented in [Data Fabric migration](docs/data-fabric-migration.md).

## Keycloak

`forwardmeasure-testcontainers-keycloak` runs a real Keycloak distribution and
imports a caller-owned realm document. It exposes password-grant and
client-credentials token helpers so application tests exercise actual issuer,
signature, audience, authorized-party, role and service-account behaviour
instead of manufacturing JWTs or mocking the identity provider.
