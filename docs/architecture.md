# Architecture

The provider module owns the container and its configuration but has no test-framework dependency. An adapter owns integration with a particular test framework.

PostgreSQL configuration supports an explicit image, database, credentials,
resource limits, caller-owned network ID, and network aliases. The fixture
attaches through the Testcontainers network API so aliases are registered with
Docker DNS. Host and container-network JDBC addresses remain distinct. Adapters
must select one explicitly rather than rewriting URLs heuristically.

The Quarkus adapter preserves support for the default datasource, named datasources and Dev Services container networking. It fails closed if a network JDBC address is requested without a network alias. It does not log credentials or create an unrelated Docker network.

Data Fabric can later replace its PostgreSQL configurator and lifecycle manager with the Quarkus adapter. Other existing container utilities should be extracted provider by provider, rather than moving the current all-dependencies utility artifact wholesale.

## Cassandra

`CassandraContainerConfiguration` is an immutable, validated record: image
(default `cassandra:5.0.5`), the `MAX_HEAP_SIZE`/`HEAP_NEWSIZE` pair (512M and
100M; the new generation must be smaller than the heap, and the heap smaller
than any memory cap), memory and memory-plus-swap limits (2 GiB and none),
password authentication, an optional classpath `cassandra.yaml` copied over
`/etc/cassandra/cassandra.yaml` before start, the startup timeout (3 minutes),
and a caller-owned network ID with aliases. `defaults()` reproduces the
fixture's long-standing settings and `with*()` methods return modified copies.

A supplied `cassandra.yaml` lets a test choose the partitioner, SSL, client
authentication and so on. The image's entrypoint still rewrites the address,
seed and snitch keys in place, so the file must keep them, including the
commented-out `# broadcast_address:` and `# broadcast_rpc_address:` lines, as
the image's own file does. Password authentication edits whichever file the
container ends up with, so it combines with a supplied file. The keyspace
helpers connect without TLS, so they do not work against a file that
requires client encryption.

`createUniqueKeyspace(prefix)` creates a keyspace (`SimpleStrategy`,
replication factor 1) whose name is the normalised prefix plus a random
suffix: lower case, `[a-z0-9_]`, starting with a letter, at most 48
characters. `dropKeyspace(name)` drops it if it exists and refuses system
keyspaces. Both use the superuser when password authentication is on.

## Shared containers

`CassandraTestContainers.shared(configuration)` and
`OpenSearchTestContainers.shared(configuration)` return one started fixture
per distinct configuration per JVM. The first caller starts it; concurrent
callers wait for that one start; a failed start is not cached. A shared
fixture belongs to the registry, not to a test: its `close()` is a no-op, so
no test or try-with-resources block can stop it under other test classes. A
JVM shutdown hook stops shared fixtures, and Ryuk removes them if the JVM dies
without running hooks.

This is the main lever for large suites: one Cassandra per test JVM and a
fresh keyspace per test class, rather than one container per class.

## JUnit Jupiter adapters

`@WithCassandraContainer` mirrors the Cassandra configuration and adds
`shared` (use the JVM-wide fixture, never closed by the class) and
`keyspacePerClass` (a unique keyspace prefixed with the test class's simple
name, created in `beforeAll` and dropped in `afterAll`).
`CassandraContainerExtension` injects `CassandraTestContainer` and
`CassandraKeyspace` parameters. `@Nested` classes inherit the enclosing
class's settings and get their own keyspace.

`@WithOpenSearchContainer` has the same `shared` attribute. Both extensions,
like the PostgreSQL one, expose static `containerFor(Class)` lookups (and
`keyspaceFor(Class)` for Cassandra): Spring's `@DynamicPropertySource` and
Micronaut's `TestPropertyProvider` need connection details before their
application context starts, through hooks that receive no JUnit
`ExtensionContext`. Both extensions can also be registered programmatically
with explicit settings on a `@RegisterExtension` field.

## Quarkus adapters

`@WithCassandraTestContainer` and `@WithOpenSearchTestContainer` carry the
container settings and the names of the configuration properties to emit,
because applications read these settings under their own keys. Exactly the
named properties are emitted; a blank name suppresses one, and the contact
point or hosts property is required.

- Cassandra: contact points (`host:port`), local datacenter, superuser
  credentials (only with password authentication) and, when
  `keyspaceProperty` is set, a unique keyspace created at start and dropped
  at stop.
- OpenSearch: hosts (`host:port`), protocol (`http` or `https`) and
  credentials (only with security enabled).

As with PostgreSQL, a Dev Services network ID is attached as a caller-owned
network with the configured alias, a network address is emitted only on
request, and requesting one without a Dev Services network fails before any
container starts. With `shared = true` the resources use the JVM-wide
fixtures and `stop()` releases only what the resource created.
