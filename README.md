# ForwardMeasure Testcontainers

Reusable, framework-neutral Testcontainers fixtures with small framework-specific lifecycle adapters.

The PostgreSQL foundation is split into three responsibilities:

- `forwardmeasure-testcontainers-postgresql` owns configuration and explicit container lifecycle.
- `forwardmeasure-testcontainers-junit-jupiter` owns per-test-class JUnit lifecycle and parameter injection.
- `forwardmeasure-testcontainers-quarkus` maps the same running fixture into Quarkus default and named datasource properties.

Fixtures never log credentials. Consumers can use the core lifecycle without JUnit or Quarkus, and every owner must close what it starts.

See [the architecture](docs/architecture.md).

The staged replacement for Data Fabric's existing Quarkus utility is documented in [Data Fabric migration](docs/data-fabric-migration.md).

## Keycloak

`forwardmeasure-testcontainers-keycloak` runs a real Keycloak distribution and
imports a caller-owned realm document. It exposes password-grant and
client-credentials token helpers so application tests exercise actual issuer,
signature, audience, authorized-party, role and service-account behaviour
instead of manufacturing JWTs or mocking the identity provider.
