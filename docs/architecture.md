# Architecture

The provider module owns the container and its configuration but has no test-framework dependency. An adapter owns integration with a particular test framework.

PostgreSQL configuration supports an explicit image, database, credentials,
resource limits, caller-owned network ID, and network aliases. The fixture
attaches through the Testcontainers network API so aliases are registered with
Docker DNS. Host and container-network JDBC addresses remain distinct. Adapters
must select one explicitly rather than rewriting URLs heuristically.

The Quarkus adapter preserves support for the default datasource, named datasources and Dev Services container networking. It fails closed if a network JDBC address is requested without a network alias. It does not log credentials or create an unrelated Docker network.

Data Fabric can later replace its PostgreSQL configurator and lifecycle manager with the Quarkus adapter. Other existing container utilities should be extracted provider by provider, rather than moving the current all-dependencies utility artifact wholesale.
