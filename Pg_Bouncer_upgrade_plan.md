# OmniDB — Secure Dual-Mode PostgreSQL Connectivity

## Full Architecture & Implementation Plan

## 1. Objective

Implement a production-grade PostgreSQL connectivity architecture where every managed PostgreSQL database can optionally be accessed through either:

1. DIRECT connection:
   Application → Database Proxy → PostgreSQL

2. POOLED connection:
   Application → Database Proxy → PgBouncer → PostgreSQL

Only the Database Proxy is externally reachable.

PostgreSQL and PgBouncer must remain private/internal services.

The application-facing contract must expose one public TCP port for both connection modes.

Example:

DIRECT:

```
postgresql://USER:PASSWORD@db.example.com:15432/DATABASE?sslmode=require
```

POOLED:

```
postgresql://USER:PASSWORD@pool.example.com:15432/DATABASE?sslmode=require
```

Both use public port 15432.

The proxy determines whether traffic is DIRECT or POOLED from the hostname/SNI.

---

# 2. Non-negotiable architecture invariants

The implementation MUST preserve these rules:

1. PostgreSQL must never be directly exposed to the Internet.
2. PgBouncer must never be directly exposed to the Internet.
3. Exactly one externally reachable database TCP port is exposed by default.
4. The public port must be configurable; use 15432 as the default example.
5. DIRECT and POOLED connections both use the public proxy port.
6. DIRECT routing uses a dedicated hostname/SNI.
7. POOLED routing uses a different hostname/SNI.
8. PostgreSQL and PgBouncer ports remain internal implementation details.
9. The proxy must not store tenant database passwords.
10. The proxy must not authenticate PostgreSQL users.
11. PostgreSQL remains the final authentication and authorization authority.
12. PgBouncer must authenticate using SCRAM.
13. PgBouncer must not use the PostgreSQL superuser as its normal authentication lookup role.
14. Each managed PostgreSQL database must have a globally unique PostgreSQL role.
15. OmniDB management traffic must connect directly to PostgreSQL.
16. OmniDB must not use PgBouncer for management operations.
17. DIRECT and POOLED endpoints must be represented as structured domain objects.
18. Connection strings must not be generated through string replacement.
19. Pooled connectivity must be validated end-to-end.
20. Password rotation and database deletion must account for existing PgBouncer sessions.
21. Transaction-pooling limitations must be clearly documented.
22. Generic MongoDB/MySQL abstractions must not be polluted with PgBouncer-specific concepts.
23. External/internal endpoint information must remain separate.
24. NSG/firewall rules must expose only the proxy port.
25. Unknown SNI/hostname routing must be rejected rather than falling back to a backend.

---

# 3. Target network architecture

The final deployment should look like:

```
                     INTERNET
                        |
                        |
                   NSG / FIREWALL
                        |
                 TCP :15432 ONLY
                        |
                        v
            +-----------------------+
            |    DATABASE PROXY    |
            |        :15432        |
            |                       |
            |     SNI routing       |
            +----------+------------+
                       |
         PRIVATE DOCKER NETWORK
                       |
          +------------+-------------+
          |                          |
          v                          v
   +--------------+          +--------------+
   | PostgreSQL   |          |  PgBouncer   |
   |    :5432     |          |    :6432     |
   | PRIVATE ONLY |          | PRIVATE ONLY |
   +--------------+          +------+-------+
                                     |
                                     |
                                     v
                              PostgreSQL :5432
```

OmniDB management path:

```
   OmniDB
      |
      | private/direct
      v
 PostgreSQL :5432
```

OmniDB does NOT use:

```
   OmniDB → PgBouncer
```

---

# 4. Public connection routing

Use two DNS names resolving to the same public IP.

Example:

```
db.example.com
pool.example.com
```

Both point to the same server/load balancer/proxy.

Both use the same public port:

```
:15432
```

Routing:

```
db.example.com:15432
        |
        v
    Database Proxy
        |
        v
   postgres:5432


pool.example.com:15432
        |
        v
    Database Proxy
        |
        v
   pgbouncer:6432
        |
        v
   postgres:5432
```

The proxy should route based on TLS SNI.

Do NOT route based on:

* username
* password
* database name
* SQL
* arbitrary PostgreSQL protocol fields
* naming conventions in usernames

The database name is not a suitable routing key because both DIRECT and POOLED modes access the same database.

---

# 5. TLS design

Preferred design:

```
Application
    |
    | TLS
    | SNI = db.example.com
    v
Database Proxy
    |
    | TLS passthrough
    v
PostgreSQL
```

and:

```
Application
    |
    | TLS
    | SNI = pool.example.com
    v
Database Proxy
    |
    | TLS passthrough
    v
PgBouncer
    |
    | TLS
    v
PostgreSQL
```

TLS passthrough is preferred because the proxy does not need access to tenant credentials or decrypted PostgreSQL traffic.

If the selected proxy cannot provide the required TLS passthrough/SNI routing behavior, TLS termination at the proxy is acceptable, but then:

1. The private backend network must be trusted.
2. Backend communication should preferably remain encrypted.
3. The TLS termination boundary must be explicitly documented.
4. The proxy must never persist tenant credentials.
5. Certificate management must be implemented securely.

Do not issue connection strings containing `sslmode=require` unless the actual public endpoint accepts TLS.

---

# 6. DNS design

Introduce configurable public database hostnames:

```
database.proxy.direct-host
database.proxy.pooled-host
```

Example:

```
direct-host = db.example.com
pooled-host = pool.example.com
```

Both resolve to the same public IP.

Do not hard-code the domain.

The deployment should support:

```
db.example.com
pool.example.com
```

or:

```
postgres.example.com
postgres-pool.example.com
```

depending on deployment configuration.

---

# 7. Internal endpoint model

Keep internal and external endpoint information separate.

Internal:

```
PostgreSQL:
    postgres:5432

PgBouncer:
    pgbouncer:6432
```

External:

```
DIRECT:
    db.example.com:15432

POOLED:
    pool.example.com:15432
```

Do not expose:

```
postgres:5432
pgbouncer:6432
```

through the application API.

---

# 8. Domain model

Do not use only:

```
boolean pooled
```

as the long-term model.

Introduce:

```
ConnectionMode
```

with:

```
DIRECT
POOLED
```

Introduce:

```
PoolMode
```

with:

```
TRANSACTION
```

Introduce:

```
ConnectionEndpoint
```

Conceptually:

```
ConnectionEndpoint
    host
    port
    database
    username
    password
    sslMode
    connectionMode
    poolMode
```

Password must only exist in-memory when necessary and must never be persisted in plaintext.

Introduce:

```
DatabaseConnections
```

containing:

```
direct
pooled
```

---

# 9. Recommended Java model

Create:

```
public enum ConnectionMode {
    DIRECT,
    POOLED
}

public enum PoolMode {
    TRANSACTION
}

public record ConnectionEndpoint(
    String host,
    int port,
    String database,
    String username,
    String password,
    SslMode sslMode,
    ConnectionMode mode,
    PoolMode poolMode
) {}

public record DatabaseConnections(
    ConnectionEndpoint direct,
    ConnectionEndpoint pooled
) {}
```

If `password` is undesirable in the endpoint object, create separate public/safe endpoint DTOs without the password and a credential-aware internal representation.

Do not expose plaintext credentials accidentally through:

* logging
* actuator
* JSON serialization
* exceptions
* audit logs
* metrics
* debugging output

---

# 10. Separate public and internal endpoint abstractions

Create:

```
InternalDatabaseEndpoint
```

and:

```
PublicConnectionEndpoint
```

Internal endpoint:

```
host
port
service
```

Public endpoint:

```
hostname
publicPort
connectionMode
tlsMode
```

Example:

```
InternalDatabaseEndpoint(
    "postgres",
    5432
)

PublicConnectionEndpoint(
    "db.example.com",
    15432,
    DIRECT,
    REQUIRE
)
```

This prevents Docker/service topology from leaking into the product API.

---

# 11. PostgreSQL role architecture

PostgreSQL roles are cluster-wide.

Therefore do not create:

```
databaseA → user = app
databaseB → user = app
```

because both refer to the same PostgreSQL role.

Every managed database must receive a globally unique PostgreSQL role.

Recommended pattern:

```
omni_<database-id>_<random-suffix>
```

Example:

```
omni_01HF7A8C_8F31A2
omni_01HF7B12_7C92D1
```

The exact format is flexible, but uniqueness must be guaranteed.

The role belongs to one managed database.

Password rotation for one database must never modify another database's credentials.

---

# 12. PostgreSQL privilege model

The current database-per-user isolation model can remain.

Conceptually:

```
tenant role
    |
    +---- owns tenant database
    |
    +---- CONNECT
    |
    +---- schema privileges
```

Continue using:

```
REVOKE CREATE ON SCHEMA public FROM PUBLIC
```

and grant appropriate privileges to the tenant role.

The current owner-based model is acceptable for the current OmniDB product.

Do not introduce a complicated owner/migration/application/readonly role hierarchy unless separately required.

That can be a future security enhancement.

---

# 13. PgBouncer architecture

Run one PgBouncer instance per PostgreSQL cluster.

Do NOT create one PgBouncer instance per managed database.

Architecture:

```
PostgreSQL Cluster
      |
      v
  PgBouncer
      |
  +---+---+
  |   |   |
 DB-A DB-B DB-C
```

Use wildcard database routing:

```
[databases]
* = host=postgres port=5432
```

This means OmniDB does not need to regenerate PgBouncer database entries for every tenant database.

---

# 14. PgBouncer pool mode

Use:

```
pool_mode = transaction
```

This is the default pooled application contract.

The pooled connection endpoint must explicitly communicate:

```
PgBouncer transaction pooling
```

Do not describe it simply as:

```
"PostgreSQL pooled"
```

because transaction pooling has PostgreSQL session-semantic limitations.

---

# 15. PgBouncer authentication

Do NOT use:

```
auth_user = postgres
```

as the long-term design.

Create a dedicated role:

```
pgbouncer_auth
```

Its purpose is authentication lookup only.

Use an explicit restricted authentication query.

The preferred architecture is:

```
PgBouncer
    |
    | auth_query
    v
restricted SECURITY DEFINER function
    |
    v
PostgreSQL authentication metadata
```

Do not grant PgBouncer broad direct access to `pg_authid`.

The `pgbouncer_auth` role must have only the privileges required for this operation.

---

# 16. SCRAM requirements

PostgreSQL:

```
password_encryption = scram-sha-256
```

PgBouncer:

```
auth_type = scram-sha-256
```

All generated tenant passwords must be stored by PostgreSQL as SCRAM credentials.

Fix any initialization logic that currently generates MD5-format PgBouncer credentials while PgBouncer is configured for SCRAM.

Do not mix:

```
auth_type = scram-sha-256
```

with:

```
md5-only userlist credentials
```

unless the exact PgBouncer compatibility behavior is deliberately verified.

The target architecture should use SCRAM consistently.

---

# 17. PgBouncer configuration

Use configurable values.

Recommended starting values for a small deployment:

```
pool_mode = transaction

max_client_conn = 1000

default_pool_size = 5

reserve_pool_size = 2

reserve_pool_timeout = 3

max_db_connections = 10
```

Do not treat these numbers as universal.

They must remain configurable.

The previous default:

```
default_pool_size = 25
```

is too aggressive as a generic per-database default when OmniDB can manage many databases.

For example:

```
50 databases × 25 backend connections
= 1250 potential backend connections
```

That can overwhelm a small PostgreSQL instance.

---

# 18. Connection budget

Introduce a PostgreSQL connection budget concept.

Do not allow:

```
PgBouncer configuration
    +
OmniDB pools
    +
PostgreSQL reserved connections
```

to accidentally exceed PostgreSQL capacity.

Conceptually:

```
PostgreSQL max_connections
      |
      +--- PostgreSQL/admin reserve
      |
      +--- OmniDB reserve
      |
      +--- tenant backend connection budget
```

The implementation should expose configurable limits rather than attempting to consume all PostgreSQL connections.

---

# 19. Three separate pooling layers

The implementation must clearly distinguish:

1. Application-side connection pool
2. PgBouncer pool
3. OmniDB internal Hikari pool

Example:

```
Application
    |
HikariCP
    |
PgBouncer
    |
PostgreSQL
```

while:

```
OmniDB
    |
HikariCP
    |
PostgreSQL
```

Do not assume that "POOLED" means the application must stop using its normal connection pool.

Applications will generally still use their normal driver pool.

PgBouncer reduces backend PostgreSQL connections.

---

# 20. OmniDB management connectivity

OmniDB must continue using direct PostgreSQL connectivity.

Management path:

```
OmniDB
   |
   v
PostgreSQL
```

Do not change it to:

```
OmniDB
   |
   v
PgBouncer
   |
   v
PostgreSQL
```

Management operations include:

* database creation
* database deletion
* role creation
* role deletion
* password reset
* privilege changes
* extension installation
* backend termination
* database inspection
* administrative queries
* schema management

These should bypass PgBouncer.

---

# 21. Connection endpoint factory

Create a dedicated service/factory:

```
PostgresConnectionEndpointFactory
```

Responsibilities:

```
buildDirectEndpoint()
buildPooledEndpoint()
```

It must know:

* public hostname
* public port
* database name
* tenant username
* tenant password
* TLS mode
* connection mode
* pool mode

It must NOT perform arbitrary string manipulation.

---

# 22. Connection string builder

Create a dedicated:

```
ConnectionStringBuilder
```

or:

```
PostgresConnectionStringBuilder
```

It should accept a structured endpoint and produce:

```
PostgreSQL URI
JDBC URL
```

Do not implement:

```
url.replace(":5432", ":6432")
```

Do not infer the public port by modifying a previously generated URL.

The endpoint object must already contain the correct public host and port.

---

# 23. Direct endpoint generation

The externally issued direct connection must look conceptually like:

```
postgresql://USER:PASSWORD@db.example.com:15432/DATABASE?sslmode=require
```

The actual hostname and port must come from configuration.

The user must never receive:

```
postgres:5432
```

or:

```
db.example.com:5432
```

unless the deployment explicitly defines that as the public endpoint.

---

# 24. Pooled endpoint generation

The externally issued pooled connection must look conceptually like:

```
postgresql://USER:PASSWORD@pool.example.com:15432/DATABASE?sslmode=require
```

The public port is the same:

```
15432
```

The hostname changes.

The user must never receive:

```
pgbouncer:6432
```

or:

```
pool.example.com:6432
```

unless 6432 has explicitly been configured as the public proxy port, which is not the target architecture.

---

# 25. API contract

Add an endpoint similar to:

```
GET /api/databases/{id}/connections
```

Return structured connection information.

Example:

```
{
  "database": "customer_db",

  "direct": {
    "enabled": true,
    "host": "db.example.com",
    "port": 15432,
    "database": "customer_db",
    "sslMode": "require",
    "mode": "DIRECT"
  },

  "pooled": {
    "enabled": true,
    "host": "pool.example.com",
    "port": 15432,
    "database": "customer_db",
    "sslMode": "require",
    "mode": "POOLED",
    "poolMode": "TRANSACTION"
  }
}
```

Do not return plaintext passwords from normal metadata endpoints.

If credential retrieval is required, use a dedicated authenticated operation.

---

# 26. UI design

Display:

```
Connection

DIRECT
PostgreSQL
Host: db.example.com
Port: 15432
SSL: Required

[Copy connection string]


POOLED
PgBouncer
Host: pool.example.com
Port: 15432
Mode: Transaction

[Copy connection string]
```

For pooled connections show:

```
Transaction pooling is enabled.
Session-local PostgreSQL state is not guaranteed
to persist between transactions.
```

Do not label it simply:

```
"Fast PostgreSQL"
```

or:

```
"Normal PostgreSQL"
```

---

# 27. Pooled enablement

Pooled connectivity should be independently configurable.

Example:

```
Database A
    DIRECT = enabled
    POOLED = enabled

Database B
    DIRECT = enabled
    POOLED = disabled
```

The PgBouncer service itself remains cluster-level.

Disabling pooled access for one database must not require stopping PgBouncer.

---

# 28. Provisioning flow

Provisioning should become:

```
Create database
      |
      v
Validate engine-specific name
      |
      v
Acquire database lifecycle lock
      |
      v
Generate globally unique PostgreSQL role
      |
      v
Create PostgreSQL role
      |
      v
Create PostgreSQL database
      |
      v
Grant privileges
      |
      v
Configure extensions
      |
      v
Store encrypted credential metadata
      |
      v
Build DIRECT endpoint
      |
      v
Build POOLED endpoint if enabled
      |
      v
Validate DIRECT connectivity
      |
      v
Validate POOLED connectivity
      |
      v
Persist healthy connection state
      |
      v
Return connection metadata
```

If pooled validation fails, do not silently report pooled connectivity as healthy.

Either:

```
fail provisioning
```

or:

```
create database with pooled status = UNAVAILABLE
```

according to the existing provisioning semantics.

Do not claim that pooled access works without testing it.

---

# 29. End-to-end pooled validation

A PgBouncer health check such as:

```
SHOW POOLS
```

only proves that PgBouncer itself is alive.

It does NOT prove:

```
tenant user
    |
    v
PgBouncer
    |
    v
tenant database
```

works.

Add a real validation:

```
Connect to public pooled endpoint
      |
      v
authenticate using tenant credentials
      |
      v
connect to tenant database
      |
      v
SELECT 1
```

The exact test may use an internal equivalent if public DNS is not available from the server, but it must exercise the actual PgBouncer route and tenant authentication.

---

# 30. Direct validation

Likewise test:

```
tenant credentials
      |
      v
public/direct proxy route
      |
      v
PostgreSQL
      |
      v
SELECT 1
```

If testing from inside the deployment, ensure the test still exercises the same routing path where possible.

---

# 31. Health model

Represent direct and pooled health separately.

Example:

```
DIRECT
    HEALTHY

POOLED
    HEALTHY
```

or:

```
DIRECT
    HEALTHY

POOLED
    UNAVAILABLE
```

or:

```
DIRECT
    FAILED

POOLED
    FAILED
```

Pooled failure must not automatically mark direct connectivity as failed.

---

# 32. Proxy health

Proxy health should verify backend reachability.

At minimum:

```
proxy process healthy
PostgreSQL backend reachable
PgBouncer backend reachable
```

But do not confuse proxy health with tenant connectivity.

There are separate levels:

```
Infrastructure health
Endpoint health
Tenant authentication health
```

---

# 33. Database deletion

Database deletion must account for both direct and pooled sessions.

Flow:

```
DELETE database
      |
      v
Acquire database lock
      |
      v
Mark database DELETING
      |
      v
Prevent new lifecycle operations
      |
      v
Terminate PostgreSQL tenant sessions
      |
      v
Evict OmniDB Hikari pool
      |
      v
Clean/invalidate PgBouncer state as required
      |
      v
DROP DATABASE
      |
      v
DROP ROLE
      |
      v
Remove metadata
      |
      v
Release lock
```

Test the case where a client is actively connected through PgBouncer.

---

# 34. Password rotation

Password rotation must be:

```
Acquire database lock
      |
      v
Generate new password
      |
      v
ALTER ROLE
      |
      v
Update encrypted credential
      |
      v
Invalidate/refresh relevant pooled state
      |
      v
Validate new direct connection
      |
      v
Validate new pooled connection
      |
      v
Persist new state
```

Do not claim that password rotation immediately terminates existing sessions unless the implementation actually terminates them.

PostgreSQL password changes primarily affect future authentication.

If immediate revocation is required, explicitly terminate existing sessions.

---

# 35. Delete/recreate security test

The following test is mandatory:

```
Create DB A
Create credential A
Connect through PgBouncer
Delete DB A
Recreate DB A
Attempt connection using credential A
MUST FAIL
```

Then:

```
Connect using newly generated credential
MUST SUCCEED
```

This prevents stale pooled sessions/credentials from accidentally surviving database recreation.

---

# 36. Proxy security

The public proxy is the Internet-facing database security boundary.

Threat model at least:

* connection exhaustion
* connection floods
* TLS handshake abuse
* brute-force authentication
* unknown SNI
* invalid TLS
* malformed PostgreSQL traffic
* backend exhaustion
* idle connection abuse
* excessive concurrent connections
* abusive client IPs

Configure:

```
max concurrent connections
connection rate limits
idle timeout
handshake timeout
backend connection timeout
source-IP restrictions where possible
```

---

# 37. NSG/firewall design

The NSG should expose only:

```
TCP 15432 → Database Proxy
```

Do NOT expose:

```
TCP 5432
TCP 6432
TCP 9813
```

Those must remain blocked externally.

Example:

```
Internet
   |
   +---- 15432 → ALLOW → Database Proxy
   |
   +---- 5432  → DENY
   |
   +---- 6432  → DENY
   |
   +---- 9813  → DENY
```

If applications run inside a private VPC/VNet, restrict source CIDRs to those networks instead of allowing the entire Internet.

---

# 38. Docker Compose networking

PostgreSQL should have no host port mapping.

Do not use:

```
ports:
  - "5432:5432"
```

PgBouncer should have no host port mapping.

Do not use:

```
ports:
  - "6432:6432"
```

Only the proxy should publish:

```
ports:
  - "15432:15432"
```

Conceptually:

```
services:

  postgres:
    expose:
      - "5432"

  pgbouncer:
    expose:
      - "6432"

  database-proxy:
    ports:
      - "15432:15432"
```

The exact Compose syntax can vary.

The security requirement is that PostgreSQL/PgBouncer are not host-published.

---

# 39. Docker network

Use a dedicated private network:

```
database-internal
```

Attach:

```
postgres
pgbouncer
database-proxy
OmniDB where required
```

Do not attach unnecessary services to this network.

The proxy needs access to:

```
postgres:5432
pgbouncer:6432
```

OmniDB needs access to:

```
postgres:5432
```

---

# 40. Network segmentation

Prefer:

```
public-facing network
        |
        v
   database-proxy
        |
        v
private database network
   /            \
  v              v
```

postgres        pgbouncer

The proxy should be the only component bridging public and private database networks.

---

# 41. Unknown SNI behavior

If a client connects to:

```
unknown.example.com:15432
```

the proxy must reject the connection.

Do NOT fall back to:

```
PostgreSQL
```

because that would effectively make PostgreSQL reachable without the intended routing identity.

Likewise, malformed/missing SNI should either be rejected or routed only through an explicitly configured non-SNI-safe path.

The preferred behavior is rejection.

---

# 42. SNI hostname security

Only explicitly configured hostnames should be valid.

Example:

```
db.example.com
pool.example.com
```

Do not accept arbitrary wildcard hostnames such as:

```
*.example.com
```

unless the proxy implementation has a deliberate mapping model.

The routing configuration should be explicit.

---

# 43. Credential security

Tenant credentials must not be handled by the proxy.

Flow:

```
OmniDB
   |
   | generate credential
   v
PostgreSQL
   |
   | encrypted metadata
   v
OmniDB
```

Application receives:

```
username
password
public host
public port
database
TLS mode
```

Proxy only sees:

```
encrypted TCP/TLS traffic
```

The proxy must not persist:

```
passwords
SCRAM secrets
connection strings
```

---

# 44. Logging restrictions

Never log:

```
password
full connection URI containing password
SCRAM secret
authorization data
```

Safe logging fields include:

```
timestamp
source IP
SNI
route
connection mode
database name where acceptable
connection success/failure
failure category
duration
```

Use redaction in exception handling.

---

# 45. Audit logging

Audit important lifecycle operations:

```
DATABASE_CREATED
DATABASE_DELETED
PASSWORD_ROTATED
POOLED_ENABLED
POOLED_DISABLED
DIRECT_CONNECTION_TESTED
POOLED_CONNECTION_TESTED
CONNECTION_ENDPOINT_CHANGED
```

Never store credentials in audit records.

---

# 46. Proxy observability

Expose metrics for:

```
active client connections
new connections/sec
rejected connections
TLS failures
SNI failures
direct connections
pooled connections
backend connection failures
connection duration
backend saturation
```

PgBouncer metrics:

```
client connections
server connections
waiting clients
pool saturation
pool utilization
database pool count
```

PostgreSQL metrics:

```
active connections
max connection utilization
locks
query latency
errors
```

---

# 47. Transaction pooling compatibility

The pooled endpoint must explicitly document that PgBouncer uses:

```
pool_mode = transaction
```

Applications must not assume that a PostgreSQL server session remains assigned to them across transactions.

Potentially problematic session-dependent features include:

```
temporary tables
session advisory locks
LISTEN/NOTIFY
session-local settings
session-specific state
long-lived session state
SQL PREPARE/EXECUTE assumptions
```

Modern PgBouncer can support protocol-level prepared statements when configured appropriately, but compatibility must be tested with the target drivers.

Do not claim universal transparent PostgreSQL session compatibility.

---

# 48. Prepared statement testing

If using PgBouncer prepared-statement support, configure it explicitly.

For example:

```
max_prepared_statements = 200
```

The value must be configurable.

Test at least the drivers relevant to the project/application ecosystem.

At minimum consider:

```
JDBC
Node.js PostgreSQL driver
```

Do not assume all drivers behave identically under transaction pooling.

---

# 49. Application connection guidance

Recommended use of DIRECT:

```
LISTEN/NOTIFY
temporary tables
session advisory locks
session state
long-lived transactions
features requiring session affinity
specialized PostgreSQL functionality
```

Recommended use of POOLED:

```
REST APIs
microservices
serverless workloads
short database transactions
high connection churn
many application instances
```

The pooled endpoint remains compatible with an application-side connection pool.

---

# 50. Generic DatabaseEngine abstraction

Do not add:

```
getPgbouncerUrl()
```

to the generic DatabaseEngine interface.

Instead expose a generic concept:

```
connectionEndpoints()
```

PostgreSQL can return:

```
DIRECT
POOLED
```

MongoDB can currently return:

```
DIRECT
```

MySQL can currently return:

```
DIRECT
```

This keeps PgBouncer PostgreSQL-specific.

---

# 51. Suggested package structure

Use something conceptually similar to:

```
database/
  domain/
    ConnectionMode
    PoolMode
    ConnectionEndpoint
    PublicConnectionEndpoint
    InternalDatabaseEndpoint
    DatabaseConnections

  service/
    ConnectionEndpointService
    ConnectionValidationService
    CredentialRotationService
    DatabaseProvisioningService

  infrastructure/
    PostgresConnectionEndpointFactory
    PostgresConnectionStringBuilder

  proxy/
    DatabaseProxyConfiguration
    DatabaseProxyHealthService

  repository/
    PostgresDatabaseRepository
```

Do not over-engineer the proxy implementation into the domain layer.

---

# 52. Configuration model

Introduce explicit configuration.

Conceptually:

```
database:
  public:
    enabled: true
    port: 15432

    direct:
      hostname: db.example.com

    pooled:
      hostname: pool.example.com

  tls:
    mode: require

  pgbouncer:
    enabled: true
    poolMode: transaction
    defaultPoolSize: 5
    reservePoolSize: 2
    maxDbConnections: 10
    maxClientConnections: 1000
```

The exact YAML structure can be adapted to the existing `application.yml`.

Do not duplicate the same setting in multiple configuration sections.

---

# 53. Environment variables

Production secrets must come from environment/secret management.

Do not ship production defaults such as:

```
admin/admin
change-me-now
```

as valid production credentials.

Production startup should reject:

* known default passwords
* missing encryption key
* invalid PgBouncer credentials
* invalid proxy configuration
* missing TLS certificates when TLS is required

Development mode may allow explicit development defaults.

---

# 54. TLS certificate management

The deployment should have certificates covering:

```
db.example.com
pool.example.com
```

A wildcard certificate may be used if appropriate.

The proxy must validate certificate/SNI configuration during startup.

If using TLS passthrough, PostgreSQL/PgBouncer must present the correct certificate depending on the chosen architecture.

If both routes ultimately require different certificates, verify the selected proxy/TLS topology supports this cleanly.

---

# 55. Public endpoint consistency validation

At startup validate:

```
publicPort > 0
directHostname != blank
pooledHostname != blank when pooled is enabled
directHostname != pooledHostname
internal PostgreSQL endpoint exists
internal PgBouncer endpoint exists when pooled is enabled
```

Fail fast on invalid routing configuration.

Do not start with an ambiguous configuration.

---

# 56. Proxy routing table

Conceptually:

```
routes:
  db.example.com:
    backend: postgres
    port: 5432

  pool.example.com:
    backend: pgbouncer
    port: 6432
```

The proxy must reject:

```
unknown hostname
```

and must not default to PostgreSQL.

---

# 57. Failure behavior

Expected behavior:

```
Proxy DOWN
    DIRECT = unavailable
    POOLED = unavailable

PostgreSQL DOWN
    DIRECT = unavailable
    POOLED = unavailable

PgBouncer DOWN
    DIRECT = available
    POOLED = unavailable

Pooled auth broken
    DIRECT = available
    POOLED = unavailable

Direct route broken
    DIRECT = unavailable
    POOLED may remain available
```

The health model must preserve this independence.

---

# 58. Database lifecycle concurrency

Preserve the existing database lock mechanism.

For a given database:

```
CREATE
RESET
DELETE
```

must not execute concurrently.

Existing process-local locking should remain.

If OmniDB is eventually deployed as multiple replicas, introduce a distributed/database-level lock.

Do not introduce distributed locking solely for this feature unless required.

---

# 59. Existing Hikari pools

Continue using OmniDB's direct Hikari pools for administrative database operations.

Do not point these pools to PgBouncer.

Maintain sensible limits:

```
maximumPoolSize
connectionTimeout
validationTimeout
idleTimeout
maxLifetime
```

The pool count should remain bounded if many managed databases exist.

---

# 60. Connection endpoint persistence

Persist only the configuration/metadata necessary to reproduce the endpoints.

Do not persist generated connection strings containing plaintext passwords.

Preferred persisted data:

```
database ID
database name
username
encrypted password
connection mode availability
endpoint configuration/version
```

Generate the final connection string at request time.

This prevents stale connection strings from becoming a source of truth.

---

# 61. Connection configuration versioning

Consider a connection endpoint configuration version.

Example:

```
endpointVersion = 1
```

If the public port or hostname changes:

```
endpointVersion = 2
```

The endpoint can be regenerated without changing tenant credentials.

This is useful when moving:

```
15432 → 25432
```

or:

```
db.example.com → postgres.example.com
```

without recreating databases.

---

# 62. Endpoint changes

If the public proxy hostname/port changes:

```
PostgreSQL database remains unchanged
credentials remain unchanged
only public endpoint metadata changes
```

After configuration change:

```
rebuild endpoints
invalidate cached endpoint data
run connectivity validation
expose new endpoints
```

Do not recreate tenant databases.

---

# 63. Connection cache

If endpoint information is cached, cache only non-secret endpoint metadata where possible.

Avoid long-lived caching of:

```
plaintext passwords
connection strings containing passwords
```

If credentials are required, retrieve/decrypt only for the specific operation.

---

# 64. Testing matrix

The feature is not complete until the following tests pass.

## Direct connectivity

```
create database
obtain direct endpoint
connect
SELECT 1
CREATE TABLE
INSERT
SELECT
```

## Pooled connectivity

```
create database
obtain pooled endpoint
connect
SELECT 1
CREATE TABLE
INSERT
SELECT
```

## Both endpoints

```
direct connection succeeds
pooled connection succeeds
both reach the same database
```

## Isolation

```
user A → database A = SUCCESS
user A → database B = FAILURE
user B → database B = SUCCESS
user B → database A = FAILURE
```

---

# 65. Proxy tests

Test:

```
db.example.com:15432
    → PostgreSQL

pool.example.com:15432
    → PgBouncer
```

Test:

```
unknown.example.com:15432
    → REJECT
```

Test:

```
wrong SNI
    → REJECT
```

Test:

```
invalid TLS
    → REJECT
```

Test:

```
public 5432
    → UNREACHABLE
```

Test:

```
public 6432
    → UNREACHABLE
```

---

# 66. Credential tests

Test:

```
create DB
old password works

rotate password

old password:
    new connection = FAIL

new password:
    new connection = SUCCESS
```

Test both:

```
DIRECT
POOLED
```

---

# 67. Delete tests

Test:

```
client connected DIRECT
delete database
```

and:

```
client connected POOLED
delete database
```

Verify that:

```
database disappears
tenant role disappears
metadata disappears
active sessions are handled according to documented semantics
stale credentials cannot reconnect
```

---

# 68. Recreate tests

Test:

```
create database
connect
delete database
recreate same database name
old credentials fail
new credentials succeed
```

Test both connection modes.

---

# 69. PgBouncer saturation tests

Simulate:

```
many application connections
```

Verify:

```
PgBouncer accepts client connections within configured limits
PostgreSQL backend connections stay within configured limits
waiting clients behave correctly
database remains responsive
proxy does not exhaust server resources
```

---

# 70. Multi-database PgBouncer test

Create:

```
DB A
DB B
DB C
```

with:

```
user A
user B
user C
```

Connect through the same PgBouncer endpoint:

```
pool.example.com:15432
```

Verify:

```
user A → DB A
user B → DB B
user C → DB C
```

and verify cross-database access is denied.

This proves wildcard PgBouncer routing works correctly.

---

# 71. Security regression tests

Automate tests proving:

```
PostgreSQL host port is not published
PgBouncer host port is not published
only proxy port is published
```

Verify NSG/deployment configuration.

Verify:

```
tenant passwords are never written to logs
```

Verify:

```
credentials are not present in normal API responses
```

Verify:

```
unknown SNI is rejected
```

Verify:

```
default production credentials are rejected
```

Verify:

```
cross-tenant database access fails
```

---

# 72. Performance tests

Measure:

```
direct connection latency
pooled connection latency
connection establishment rate
PgBouncer reuse rate
PostgreSQL backend connection count
```

Compare:

```
100 application connections direct
```

versus:

```
100 application connections through PgBouncer
```

The goal is not necessarily lower query latency.

The primary benefit is backend connection management and reuse.

---

# 73. Documentation requirements

Update the README/documentation to explain:

```
DIRECT
POOLED
```

with examples.

Document:

```
public proxy port
public hostnames
TLS requirement
transaction pooling
session limitations
application-side pooling
recommended workloads
network architecture
```

Clearly state:

```
PostgreSQL :5432 is internal.
PgBouncer :6432 is internal.
Applications connect through the public proxy port.
```

---

# 74. Example documentation

Application developers should see something like:

```
DIRECT CONNECTION

Host:
    db.example.com

Port:
    15432

Database:
    customer_db

Mode:
    Direct PostgreSQL

TLS:
    Required


POOLED CONNECTION

Host:
    pool.example.com

Port:
    15432

Database:
    customer_db

Mode:
    PgBouncer / Transaction Pooling

TLS:
    Required
```

The internal ports 5432 and 6432 should not appear in application-facing documentation.

---

# 75. Migration strategy

Implement incrementally.

## Phase 1 — Domain

Add:

```
ConnectionMode
PoolMode
ConnectionEndpoint
PublicConnectionEndpoint
InternalDatabaseEndpoint
DatabaseConnections
```

Keep existing functionality working.

## Phase 2 — PostgreSQL role uniqueness

Change tenant role generation to guarantee cluster-wide uniqueness.

Add compatibility/migration handling for existing databases.

## Phase 3 — Endpoint factory

Centralize direct/pooled endpoint generation.

Remove URL string replacement.

## Phase 4 — Secure PgBouncer authentication

Add:

```
pgbouncer_auth
```

and explicit:

```
auth_query
```

Fix SCRAM credential handling.

## Phase 5 — Database Proxy

Add the single public TCP proxy.

Implement:

```
SNI
DIRECT route
POOLED route
unknown-SNI rejection
TLS
limits
health checks
```

## Phase 6 — Docker/network changes

Remove public host mappings for:

```
PostgreSQL
PgBouncer
```

Expose only:

```
proxy:15432
```

## Phase 7 — Provisioning

Generate and validate both endpoint types.

## Phase 8 — API

Expose structured connection endpoint information.

## Phase 9 — UI

Show:

```
Direct
Pooled
```

with transaction pooling warning.

## Phase 10 — Lifecycle

Integrate rotation/delete/session invalidation.

## Phase 11 — Integration tests

Add the full connectivity/security matrix.

## Phase 12 — Documentation

Document the final architecture and application contract.

---

# 76. Backward compatibility

Existing installations may currently have:

```
pooled = true/false
```

Do not immediately break existing metadata.

Introduce compatibility mapping:

```
pooled = false
    → DIRECT only

pooled = true
    → DIRECT + POOLED
```

Then migrate toward the richer endpoint model.

Existing direct URLs should be regenerated using the new public proxy endpoint.

Do not preserve old externally exposed PostgreSQL/PgBouncer ports merely for compatibility if the security requirement is to remove those public ports.

---

# 77. Important implementation constraint

Do not implement the proxy by putting application logic into the proxy.

The proxy is a network routing component.

It should NOT:

```
parse SQL
inspect tenant passwords
maintain tenant database metadata
query MongoDB
query OmniDB
make provisioning decisions
authorize tenants
rewrite PostgreSQL commands
```

Its job is:

```
accept connection
inspect routing identity
establish backend connection
proxy bytes
enforce network-level limits
```

PostgreSQL remains responsible for database authentication/authorization.

---

# 78. Final architecture

The complete architecture is:

```
                          INTERNET
                              |
                              |
                        NSG / FIREWALL
                              |
                      TCP 15432 ONLY
                              |
                              v
                +--------------------------+
                |     DATABASE PROXY       |
                |                          |
                |        :15432            |
                |                          |
                |  TLS/SNI routing         |
                +------------+-------------+
                             |
              PRIVATE DATABASE NETWORK
                             |
                +------------+-------------+
                |                          |
                v                          v
         +-------------+            +-------------+
         | PostgreSQL  |            |  PgBouncer  |
         |   :5432     |            |   :6432     |
         | PRIVATE     |            | PRIVATE     |
         +-------------+            +------+------+
                                          |
                                          v
                                     PostgreSQL
```

Application DIRECT:

```
Application
    |
    | TLS
    | db.example.com:15432
    v
Database Proxy
    |
    v
PostgreSQL :5432
```

Application POOLED:

```
Application
    |
    | TLS
    | pool.example.com:15432
    v
Database Proxy
    |
    v
PgBouncer :6432
    |
    v
PostgreSQL :5432
```

OmniDB:

```
OmniDB
    |
    | private/direct
    v
PostgreSQL :5432
```

---

# 79. Final connection model

For every managed PostgreSQL database:

```
                Managed Database
                       |
          +------------+------------+
          |                         |
          v                         v
     DIRECT ENDPOINT          POOLED ENDPOINT
          |                         |
    db.example.com            pool.example.com
          |                         |
        :15432                    :15432
          |                         |
          v                         v
       PROXY                     PROXY
          |                         |
          v                         v
    PostgreSQL                 PgBouncer
        :5432                     :6432
                                    |
                                    v
                               PostgreSQL
                                  :5432
```

Both ultimately access the same PostgreSQL database and use the same tenant credentials.

The only difference is the connection path.

---

# 80. Definition of done

The implementation is complete only when all of the following are true:

[ ] PostgreSQL has no public port.

[ ] PgBouncer has no public port.

[ ] Exactly one public database TCP port exists.

[ ] DIRECT and POOLED both use that public port.

[ ] DIRECT uses dedicated SNI/hostname.

[ ] POOLED uses dedicated SNI/hostname.

[ ] Unknown SNI is rejected.

[ ] TLS is correctly configured.

[ ] Tenant authentication uses SCRAM.

[ ] PgBouncer uses a dedicated authentication lookup role.

[ ] PostgreSQL superuser is not used as the normal PgBouncer auth lookup role.

[ ] Every managed PostgreSQL database has a unique PostgreSQL role.

[ ] Cross-database access is denied.

[ ] OmniDB management traffic bypasses PgBouncer.

[ ] PgBouncer is cluster-level rather than per-database.

[ ] PgBouncer uses transaction pooling.

[ ] Pool sizes are configurable and conservative.

[ ] PostgreSQL connection budget is respected.

[ ] Direct endpoint is validated end-to-end.

[ ] Pooled endpoint is validated end-to-end.

[ ] Password rotation works for both endpoints.

[ ] Database deletion works with active direct sessions.

[ ] Database deletion works with active pooled sessions.

[ ] Database recreation invalidates old credentials.

[ ] Credentials are never logged.

[ ] Credentials are not returned by normal metadata APIs.

[ ] Public/internal endpoints are modeled separately.

[ ] Connection strings are generated centrally.

[ ] No URL string replacement is used to switch ports.

[ ] UI clearly distinguishes Direct vs Transaction Pooled.

[ ] Documentation explains transaction-pooling limitations.

[ ] Integration tests cover multi-database PgBouncer routing.

[ ] Security tests prove 5432/6432 are externally unreachable.

[ ] Proxy connection/rate/timeout limits exist.

[ ] Proxy, PgBouncer, and PostgreSQL metrics are available.

[ ] Production startup rejects insecure/default secrets.

[ ] Existing provisioning/delete/reset locking remains intact.

[ ] MongoDB/MySQL abstractions remain unaffected.

---

# 81. Primary design principle

The final product abstraction should be:

```
"OmniDB provisions a PostgreSQL database and provides
 secure application connection endpoints."
```

Not:

```
"OmniDB provisions a PostgreSQL database and optionally
 changes its port to PgBouncer."
```

A managed database has one database identity and credentials, but can expose multiple connection paths:

```
DIRECT
    full PostgreSQL session semantics

POOLED
    PgBouncer transaction pooling
```

Both paths are delivered through the same secure public database gateway.

The database infrastructure remains private.

The proxy is the only public database ingress.

PostgreSQL remains the source of truth for authentication, authorization, isolation, and database state.
