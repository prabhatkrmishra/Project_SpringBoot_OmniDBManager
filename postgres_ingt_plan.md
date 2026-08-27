# Postgres Integration Plan — Dual-Engine (MongoDB + PostgreSQL 18) + Explorer + Provision-First Nav

> Planning only — no code changed. Verified against `pom.xml` (Boot 4.1 / Java 25), `compose.yaml`, `application.yml`, `fragments/nav.html`, `index.html`, `provision.html`, `database.html`, `site.css`, `SecurityConfig`, `MongoExpressProxyFilter`, `ProvisioningService`, `ManagedDatabase`.
> Research pass: 2026-08-27 — Spring Boot 4.1.1 ref, PG 18.6 docs (CREATE DATABASE/ROLE, GRANT, Privileges, pg_hba, libpq, release notes), Docker Hub postgres/adminer tags, Testcontainers PG module, Adminer 6.0.1, OWASP DB/Crypto cheatsheets.

---

## 0. Research Summary (what changed the plan)

| Source | Finding | Impact on plan |
|---|---|---|
| **Spring Boot 4.1.1 ref** (`docs.spring.io/spring-boot/reference/`) | Boot 4.1 still uses `spring.datasource.*` + Hikari auto-config; `spring-boot-starter-jdbc` is correct for `JdbcTemplate`. Docker Compose support auto-starts `compose.yaml` services in dev; Testcontainers `@ServiceConnection` supported. | Keep `spring.datasource.url/username/password/hikari.*` as planned. |
| **Spring Boot 4.1 Release Notes** | No JDBC breaking changes. New `spring.datasource.connection-fetch=lazy` optional. | Optionally set `connection-fetch=lazy` for PG admin DataSource. |
| **PostgreSQL 18.6 — CREATE DATABASE** | `CREATE DATABASE` **cannot be executed inside a transaction block**. Requires superuser or `CREATEDB`. Supports `OWNER`, `TEMPLATE`, `ENCODING`, `LOCALE`, `STRATEGY`. | `PostgresDatabaseRepository` must **not** be `@Transactional`; use auto-commit. Use `CREATE DATABASE "name" OWNER "user"` in one statement. |
| **PostgreSQL 18.6 — CREATE ROLE** | `CREATE ROLE name WITH LOGIN PASSWORD '...'` — cluster-wide. `PASSWORD` stored per `password_encryption` (scram-sha-256 default). MD5 deprecated in PG18. | Use `CREATE ROLE "user" WITH LOGIN PASSWORD ?` parameterized. PG18 default `scram-sha-256` — no `ENCRYPTED` needed. |
| **PostgreSQL 18.6 — GRANT / Privileges** (`sql-grant.html`, `ddl-priv.html`) | DB privileges: `CREATE`, `CONNECT`, `TEMPORARY` (`CTc`). Schema: `USAGE`, `CREATE` (`UC`). Table: `arwdDxtm`. Default PUBLIC on DB is `CONNECT,TEMPORARY`; on `public` schema PUBLIC has `USAGE,CREATE` pre-PG15, revoked in PG15+. `ALTER DEFAULT PRIVILEGES` controls future objects. | Enterprise least-privilege: `GRANT CONNECT ON DATABASE`, `GRANT USAGE,CREATE ON SCHEMA public`, `ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES/SEQUENCES` — not `ALL ON DATABASE` (which would include `TEMP`). Revoke PUBLIC CREATE if needed. |
| **PostgreSQL 18.6 — pg_hba.conf** | `host`/`hostssl`/`hostnossl` control TLS. `scram-sha-256` is current auth. `hostssl` enforces TLS. | For enterprise, set `hostssl` + `scram-sha-256` in `pg_hba.conf` via `POSTGRES_INITDB_ARGS` or custom config; app issues `sslmode=require` or `verify-full`. |
| **PostgreSQL 18.6 — libpq Connect** | URI `postgresql://user:pass@host:5432/db?sslmode=require&application_name=...` — `sslmode` values: `disable,allow,prefer,require,verify-ca,verify-full`. `verify-full` validates CA + hostname. | Enterprise: `sslmode=require` minimum when `POSTGRES_PUBLIC_TLS=true`; `verify-full` when CA available. Add `application_name=mongodbserver` for audit. |
| **PostgreSQL 18 Release Notes** | `initdb` now `--data-checksums` default. `PGDATA` path changed for PG18+ to `/var/lib/postgresql/18/docker`. | Compose volume must target `/var/lib/postgresql` with `PGDATA=/var/lib/postgresql/18/docker`. |
| **Docker Hub — postgres** | Tags: `18.6`, `18`, `18.6-alpine3.24`, `18-alpine`. `PGDATA` change documented. | Pin `postgres:18.6-alpine` (~80 MB). Use `volumes: [postgres-data:/var/lib/postgresql]`. |
| **Docker Hub — adminer** | Tags: `6.0.1-standalone`, `standalone`. 41.6 MB. Supports PG out of box. | Pin `adminer:6.0.1-standalone`. `ADMINER_DEFAULT_SERVER=postgres`. |
| **Testcontainers — Postgres Module** | `PostgreSQLContainer("postgres:18.6-alpine")`, JDBC URL `jdbc:tc:postgresql:18.6:///db`. | Integration tests use `new PostgreSQLContainer("postgres:18.6-alpine")` with `@ServiceConnection`. |
| **OWASP DB Security / Crypto** | Least privilege, TLS 1.2+, strong passwords, no `root`/`sa`, separate DB per app, encrypt at rest, audit. AES-256-GCM for app-level encryption, scram-sha-256 for PG. | Apply: per-DB user with minimal grants, TLS enforced, `storedPassword` encrypted at rest (Phase 4), audit trail per engine. |

---

## 1. Stack Pin

### Java 25 — no `pom.xml` version change

- Already `java.version=25` + `spring-boot-starter-parent:4.1.0` (Java 25-native, verified against Boot 4.1.1 ref).
- Keep `deploy.sh` flags: `-XX:+UseCompactObjectHeaders` (Java 25+), `-XX:+UseSerialGC`, `-Xms64m -Xmx256m`.
- New code may use:
  - `Executors.newVirtualThreadPerTaskExecutor()` (Java 21+) to replace `StatisticsService:88` `newFixedThreadPool(16)` for `collStats` + `pg_stat` fan-out.
  - Records + pattern matching (already in use) — continue.

### PostgreSQL 18.6 — GA 2025-09-25, latest patch 18.6 (2026-08-13)

- Pin `postgres:18.6-alpine` (or `18-alpine` for auto-patch). `18.6-trixie` if Debian preferred.
- Driver `org.postgresql:postgresql:42.7.5` supports PG18 + Java 25.
- DDL unchanged from PG17 for this app. PG18 adds async I/O + improved vacuum — no app code impact.
- **Docker PGDATA change (PG18+):** mount at `/var/lib/postgresql` (or set `PGDATA=/var/lib/postgresql/18/docker`), not `/var/lib/postgresql/data` (pre-18 path).

### Explorer — Adminer 6.0.1-standalone (recommended, enterprise-compatible)

| Option | Image | Size | Auth | Proxy complexity | Verdict |
|---|---|---|---|---|---|
| **Adminer** | `adminer:6.0.1-standalone` | 41.6 MB | None (relies on app login) | Trivial — single PHP, no session | **Recommended** — matches small-VPS guardrails, enterprise can swap to pgAdmin |
| pgAdmin4 | `dpage/pgadmin4:9.x` | ~300 MB | Own email/password + session cookie | Hard — CSRF, `X-CSRFToken`, cookie rewrite | Enterprise alternative if full pgAdmin needed; document as swap |
| pgweb | `sosedoff/pgweb` | ~15 MB (Go) | None | Trivial | Light alternative |

---

## 2. Goal / Non-Goals

**Goal (enterprise-grade):** Provision **MongoDB** and **PostgreSQL 18** as **fully separate engines** — separate nav, routes, connection strings, health, audit, and explorer. Same DB name allowed across engines (e.g. `myapp` in both). Each DB gets a dedicated per-DB user with **least-privilege** grants, TLS-ready connection strings, and audit trail. Both engines coexist; metadata stays in `mongodb_admin` with `engineType`.

**Non-goals (Phase 1):**

- Move metadata (`provisioned_databases`, `admin_activity`) to Postgres/JPA
- Postgres exploration (tables/documents), `collStats`/`dbStats` parity, backup/restore, `pg_dump`
- Replace Mongo — Mongo stays default

**Enterprise-grade additions (Phase 1):**

- Least-privilege PG grants (not `ALL`), `scram-sha-256`, TLS via `sslmode`, `application_name`
- Separate engine contexts — no mixed lists, no cross-engine leakage
- Composite identity `engine:dbName` — same name allowed

---

## 3. Key Design Decisions (updated per your choices)

| Decision | Choice | Why |
|---|---|---|
| **Metadata store** | Keep in Mongo (`mongodb_admin`) | Zero migration, backward compat. Add `engineType` field to `ManagedDatabase`. |
| **Engine abstraction** | `DatabaseEngine` interface + `MongoEngine` (wraps `MongoDatabaseRepository`) + `PostgresEngine` (new `PostgresDatabaseRepository` via `JdbcTemplate`) | Isolates driver specifics; `ProvisioningService` becomes orchestrator only. Engines never share state. |
| **DB identity** | **Composite `engine + dbName` — same name ALLOWED across engines** | User requested `myapp` can exist in both. `ManagedDatabase.id = engine + ":" + dbName`, unique index on `(engineType, dbName)`. `DatabaseLockRegistry` key = `engine:dbName`. Routes are prefixed so no ambiguity. |
| **Route scheme** | **Prefix mandatory: `/mongo/databases/{name}` + `/postgres/databases/{name}`** | User requested. No fallback to `/databases/{name}` (or 301 redirect for backward compat). Nav active state is unambiguous. |
| **Connection strings** | `mongodb://user:pass@host/db?authSource=db` vs `postgresql://user:pass@host:5432/db?sslmode=require&application_name=mongodbserver` | PG string built from `app.postgres.public-host` / `spring.datasource` host, with `uriEncode` reuse. Enterprise: `sslmode=require` when `POSTGRES_PUBLIC_TLS=true`, `verify-full` when CA available. `application_name` for audit. |
| **Validation** | Extend `MongoNameValidator` → `DatabaseNameValidator` with `validatePostgres*` | PG identifiers: `^[a-z_][a-z0-9_]*$` must start with letter, lowercased, max 63 chars (PG limit). Mongo keeps `[A-Za-z0-9_-]+`. |
| **SQL safety** | Never interpolate identifiers; use `quoteIdentifier()` + `PreparedStatement` for passwords | Prevents SQL injection. |
| **Admin DataSource** | Separate `postgresAdminDataSource` (superuser) for DDL; `MongoClient` unchanged | PG `CREATE DATABASE` requires superuser or `CREATEDB`. Use `spring.datasource` with `POSTGRES_ROOT_USER`. Engines have separate pools (Hikari 5 for PG, 10 for Mongo). |
| **Transaction handling** | **No `@Transactional` on PG DDL** — `CREATE/DROP DATABASE` cannot run inside transaction | `JdbcTemplate` auto-commit. |
| **Explorer** | Adminer `6.0.1-standalone` loopback-bound, proxied behind app login | Same pattern as `mongo-express`. 502 when down. Enterprise can swap to `dpage/pgadmin4` via one-line compose change. |
| **Separation** | **Mongo and Postgres fully separate** — separate nav, controllers, services, health, stats, audit `engine` field | No mixed `listDatabases()`; dashboard shows two separate cards/tables. Prevents cross-engine confusion. |
| **Privileges (enterprise)** | **Least-privilege, not `ALL`** — `CONNECT` on DB, `USAGE,CREATE` on `public` schema, `ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES/SEQUENCES` | OWASP least privilege. `ALL ON DATABASE` would grant `TEMP` unnecessarily; `ALL ON SCHEMA` is correct for `public`. Future tables auto-granted. |

---

## 4. File-Level Change Plan

### 4.1 Build / Infra

**`pom.xml`** — add (Boot 4.1.1 managed versions):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
<!-- test -->
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

Keep `spring-boot-starter-data-mongodb`. Boot 4.1.1 manages `postgresql:42.7.5` and `testcontainers:2.0.5` via BOM — no version needed.

**`compose.yaml`** — add services (mirrors `mongo:2` + `mongo-express:32`, with PG18 PGDATA fix):

```yaml
  postgres:
    image: postgres:18.6-alpine
    container_name: mongodbserver-postgres
    restart: unless-stopped
    ports: ["127.0.0.1:9813:5432"]
    environment:
      POSTGRES_USER: ${POSTGRES_ROOT_USER:-root}
      POSTGRES_PASSWORD: ${POSTGRES_ROOT_PASSWORD:-root}
      PGDATA: /var/lib/postgresql/18/docker
    volumes: [postgres-data:/var/lib/postgresql]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5
    # enterprise: enforce scram-sha-256 + hostssl (optional, via custom pg_hba)
    # command: ["postgres", "-c", "password_encryption=scram-sha-256", "-c", "ssl=on"]

  adminer:
    image: adminer:6.0.1-standalone
    container_name: mongodbserver-adminer
    restart: unless-stopped
    ports: ["127.0.0.1:9815:8080"] # loopback only, like mongo-express:38
    environment: {ADMINER_DEFAULT_SERVER: postgres}
    depends_on: [postgres]

volumes: {mongo-data:, postgres-data:}
```

**`.env.example`** — add:

```
POSTGRES_ROOT_USER=root
POSTGRES_ROOT_PASSWORD=change-me-now
# POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable
# POSTGRES_PUBLIC_HOST=public.domain.name.com
# POSTGRES_PUBLIC_TLS=false
# POSTGRES_PUBLIC_SSLMODE=require # or verify-full when CA available
# ADMINER_BASE_URL=http://127.0.0.1:9815
```

**`src/main/resources/application.yml`** — add:

```yaml
app.postgres.enabled: ${POSTGRES_ENABLED:false}
app.postgres.uri: ${POSTGRES_URI:jdbc:postgresql://127.0.0.1:9813/postgres}
app.postgres.public-host: ${POSTGRES_PUBLIC_HOST:}
app.postgres.public-tls: ${POSTGRES_PUBLIC_TLS:false}
app.postgres.public-sslmode: ${POSTGRES_PUBLIC_SSLMODE:require} # enterprise: require or verify-full
app.adminer.base-url: ${ADMINER_BASE_URL:http://127.0.0.1:9815}
spring.datasource: # conditional on app.postgres.enabled — Boot 4.1 auto-config
  url: ${app.postgres.uri}
  username: ${POSTGRES_ROOT_USER:root}
  password: ${POSTGRES_ROOT_PASSWORD:root}
  hikari.maximum-pool-size: 5
  hikari.minimum-idle: 1
```

### 4.2 Domain / DTO

- **`model/DatabaseEngineType.java`** (new enum) — `MONGO, POSTGRES`
- **`model/ManagedDatabase.java`** — add `DatabaseEngineType engineType` (default `MONGO` for existing docs), update constructor, getter/setter. **Change `id` to `engine + ":" + dbName`** (e.g. `MONGO:myapp`, `POSTGRES:myapp`) to allow same name across engines. Add unique index on `(engineType, dbName)`. Migration: existing docs without `engineType` read as `MONGO`.
- **`dto/DatabaseInfo.java`** — add `DatabaseEngineType engineType` field.
- **`dto/CreateDatabaseForm.java`** — add `DatabaseEngineType engineType` (`@NotNull`), keep `dbName/userName/password`.
- **`dto/DatabaseUser.java`** — already generic; reuse for Postgres (roles = `CONNECT, CREATE` etc).
- **`model/AuditEvent.java`** — add `engineType` field for per-engine audit.

### 4.3 Repository Layer

**`repository/PostgresDatabaseRepository.java`** (new) — `JdbcTemplate` based, **no `@Transactional`**:

- `listDatabaseNames()` → `SELECT datname FROM pg_database WHERE datistemplate=false AND datname NOT IN ('postgres')`
- `databaseExists(db)` → `SELECT 1 FROM pg_database WHERE datname=?`
- `getDatabaseSize(db)` → `SELECT pg_database_size(?)`
- `createDatabase(db, owner)` → `CREATE DATABASE "db" OWNER "owner" TEMPLATE template0 ENCODING 'UTF8'` — `quoteIdentifier()` for both. Outside transaction.
- `dropDatabase(db)` → `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=? AND pid <> pg_backend_pid()` then `DROP DATABASE IF EXISTS "db"` (outside transaction)
- `createUser(db, user, pass)` → `CREATE ROLE "user" WITH LOGIN PASSWORD ?` (param) + `GRANT CONNECT ON DATABASE "db" TO "user"` + `GRANT USAGE, CREATE ON SCHEMA public TO "user"` + `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO "user"` + `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO "user"` — least privilege, future objects auto-granted. `REVOKE CREATE ON SCHEMA public FROM PUBLIC` if hardening.
- `updateUserPassword(db, user, pass)` → `ALTER ROLE "user" WITH PASSWORD ?`
- `dropUser(db, user)` → `REVOKE ALL ON DATABASE "db" FROM "user"` + `REVOKE ALL ON SCHEMA public FROM "user"` then `DROP ROLE IF EXISTS "user"`
- `getUsers(db)` → `SELECT usename FROM pg_user WHERE has_database_privilege(usename, db, 'CONNECT')`
- `listTables(db)` — deferred to Phase 3

**`repository/MongoDatabaseRepository.java`** — no logic change; wrap as `MongoDatabaseEngine`.

### 4.4 Service Layer

**`service/DatabaseEngine.java`** (new interface):

```java
interface DatabaseEngine {
  DatabaseEngineType type();
  void createUser(String db, String user, String pass);
  void createDatabase(String db);
  void dropDatabase(String db);
  void dropUser(String db, String user);
  void updateUserPassword(String db, String user, String pass);
  boolean databaseExists(String db);
  List<String> listDatabaseNames();
  Map<String,Long> getDatabaseSizes();
  String buildConnectionString(String user, String pass, String db);
  List<Document> getUsers(String db);
}
```

- **`service/MongoDatabaseEngine.java`** — delegates to `MongoDatabaseRepository`, `buildConnectionString` via `uriEncode` + `resolveConnectionHost()`.
- **`service/PostgresDatabaseEngine.java`** — delegates to `PostgresDatabaseRepository`, builds `postgresql://user:pass@host:5432/db?sslmode=require&application_name=mongodbserver` (or `verify-full` when `public-sslmode=verify-full`). Reuse `uriEncode`.
- **`service/ProvisioningService.java`** — refactor to keep engines separate:
  - Inject `Map<DatabaseEngineType, DatabaseEngine> engines`.
  - `provision(form)` → `engines.get(form.engineType()).createUser/createDatabase`, store `engineType` in `ManagedDatabase` with composite id, `buildConnectionString` via engine. No `@Transactional` on PG path.
  - `resetPassword(engine, dbName, form)` → lookup metadata by `engine:dbName`, delegate `updateUserPassword`.
  - `delete(engine, dbName)` → lookup by `engine:dbName`, delegate `dropDatabase/dropUser`.
  - `listDatabases(engine)` → per-engine list (no merge). Dashboard calls both separately.
  - `getDatabase(engine, dbName)` → find by composite id.
  - `listUsers/revokeUser` → per-engine.
  - `createCollection/dropCollection` → only for `MONGO`; PG throws `NameNotAllowedException`.
  - Lock key: `engine + ":" + dbName` — allows `myapp` in both engines concurrently.
- **`service/MongoNameValidator.java`** → `DatabaseNameValidator` with `validatePostgresDatabaseName`/`validatePostgresUserName` (PG: `^[a-z_][a-z0-9_]*$`, max 63, lowercased).
- **`service/ExplorationService.java`, `StatisticsService.java`, `BackupService.java`, `HealthService.java`** — Phase 1: engine check, throw or return empty for Postgres. Phase 3: PG equivalents. Consider `newVirtualThreadPerTaskExecutor()` for stats fan-out.

### 4.5 Controller / View — Fully Separate

- **`controller/MongoController.java`** (new) + **`controller/PostgresController.java`** (new) — or single `DatabaseController` with `@RequestMapping("/{engine}")` prefix. Routes:
  - `GET /mongo` → Mongo home (lists only Mongo DBs)
  - `GET /postgres` → Postgres home (lists only Postgres DBs)
  - `GET /mongo/databases/{name}` → Mongo detail
  - `GET /postgres/databases/{name}` → Postgres detail
  - `POST /mongo/databases` + `POST /postgres/databases` → provision per engine (or single `POST /databases` with `engineType` param, but prefix is cleaner for enterprise)
  - `POST /mongo/databases/{name}/reset` + `POST /postgres/databases/{name}/reset`
  - `POST /mongo/databases/{name}/delete` + `POST /postgres/databases/{name}/delete`
  - Legacy `GET /databases/{name}` → 301 redirect to `/{engine}/databases/{name}` via metadata lookup (or 404 if ambiguous).
- **`controller/ProvisionController.java`** (new) — `GET /provision` chooser, `GET /provision/mongo`, `GET /provision/postgres` forms.
- **`controller/DashboardController.java`** — add `mongoCount`, `postgresCount`, `mongoReachable`, `postgresReachable` to model. No mixed list.
- **Thymeleaf** — `provision.html` becomes chooser; `provision-mongo.html`/`provision-postgres.html` forms with `engineType` hidden. `index.html` shows two separate sections (Mongo table + Postgres table) with engine badges. `database.html` conditional: `th:if="${database.engineType=='MONGO'}"` show Collections, `th:if="${database.engineType=='POSTGRES'}"` show Tables (Phase 3). Connection string label: `MongoDB URI` vs `PostgreSQL URI` with `sslmode` hint.
- **`config/SecurityConfig.java:45`** — add `.requestMatchers("/mongo/**").hasRole("ADMIN")` for writes, `.requestMatchers("/postgres/**").hasRole("ADMIN")`, `.requestMatchers("/adminer/**").hasRole("ADMIN")`, `.csrf(csrf->csrf.ignoringRequestMatchers("/mongo-express/**","/adminer/**"))`.

### 4.6 Config

- **`config/PostgresConfig.java`** (new) — `@ConditionalOnProperty("app.postgres.enabled")` creates `DataSource` + `JdbcTemplate` + `PostgresDatabaseRepository` + `PostgresDatabaseEngine` beans. No `@Transactional` on DDL beans. Hikari `maximumPoolSize=5`, `minimumIdle=1`.
- **`config/AdminerProxyFilter.java`** (new) — clone of `MongoExpressProxyFilter:36` — `PROXY_PREFIX="/adminer"`, `targetBase=http://127.0.0.1:9815`, same `NON_FORWARDED_HEADERS`, `HttpClient` (5s connect, 60s timeout), `Location` rewrite, 502/400 handling.
- **`config/MongoIndexInitializer.java`** — add compound index on `provisioned_databases` `(engineType, dbName)` unique, plus index on `engineType`.

---

## 5. Postgres DDL Details (PG 18.6 — enterprise least-privilege)

```sql
-- provision (outside transaction block)
CREATE ROLE "myapp_user" WITH LOGIN PASSWORD '...' ; -- scram-sha-256 default
CREATE DATABASE "myapp" OWNER "myapp_user" TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'en_US.UTF-8' LC_CTYPE 'en_US.UTF-8';
-- least-privilege grants (OWASP: minimal permissions)
GRANT CONNECT ON DATABASE "myapp" TO "myapp_user";
-- connect to "myapp" to grant schema privileges
\c "myapp"
GRANT USAGE, CREATE ON SCHEMA public TO "myapp_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO "myapp_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO "myapp_user";
-- optional hardening: revoke public create (PG15+ already revoked, but explicit)
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- reset (can be in transaction)
ALTER ROLE "myapp_user" WITH PASSWORD '...';

-- delete (DROP DATABASE cannot be in transaction)
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='myapp' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "myapp";
-- revoke before drop role
REVOKE ALL ON DATABASE "myapp" FROM "myapp_user";
DROP ROLE IF EXISTS "myapp_user";

-- sizes
SELECT datname, pg_database_size(datname) FROM pg_database WHERE datistemplate=false;
-- users with CONNECT on db
SELECT usename FROM pg_user WHERE has_database_privilege(usename, 'myapp', 'CONNECT');
```

Identifier quoting mandatory — PG folds unquoted to lower case. Passwords parameterized (`?`). `CREATE DATABASE` clones `template1` by default; use `TEMPLATE template0` for pristine DB.

**Connection strings (enterprise):**

```
# MongoDB (existing)
mongodb://myapp_user:MyStrongPass@host:27017/myapp?authSource=myapp&tls=true

# PostgreSQL — without TLS (dev)
postgresql://myapp_user:MyStrongPass@127.0.0.1:9813/myapp?application_name=mongodbserver

# PostgreSQL — with TLS (enterprise, POSTGRES_PUBLIC_TLS=true)
postgresql://myapp_user:MyStrongPass@postgres.example.com:5432/myapp?sslmode=require&application_name=mongodbserver
# or with CA verification
postgresql://myapp_user:MyStrongPass@postgres.example.com:5432/myapp?sslmode=verify-full&sslrootcert=/path/to/ca.crt&application_name=mongodbserver

# JDBC alternative for Java clients
jdbc:postgresql://postgres.example.com:5432/myapp?sslmode=require&ApplicationName=mongodbserver
```

`sslmode` per libpq docs: `require` (TLS required, no CA check), `verify-ca` (CA check), `verify-full` (CA + hostname). Enterprise should use `verify-full` when CA available.

---

## 6. Explorer — Adminer Proxy Spec

- **Image:** `adminer:6.0.1-standalone` (41.6 MB). Swap to `dpage/pgadmin4:9.x` is one-line `compose.yaml` change for enterprise needing full pgAdmin.
- **Binding:** `127.0.0.1:9815:8080` loopback only, like `mongo-express:38`.
- **Env:** `ADMINER_DEFAULT_SERVER=postgres`, optional `ADMINER_DESIGN`, `ADMINER_PLUGINS`.
- **Proxy:** `AdminerProxyFilter` cloning `MongoExpressProxyFilter`:
  - `PROXY_PREFIX="/adminer"`, `targetBase=http://127.0.0.1:9815`
  - Same `NON_FORWARDED_HEADERS`, same `HttpClient` (5s connect, 60s timeout), same `Location` rewrite, same 502/400 handling.
  - Behind `SecurityConfig` — only `hasRole('ADMIN')` reaches it.
- **Sidebar links (separate):**
  - Mongo context → `↗ Mongo Express` (`/mongo-express`)
  - Postgres context → `↗ Adminer` (`/adminer`)
  - Root → both links under Explorers section

---

## 7. UI Plan — Provision-First + Engine-Scoped Nav with Back (fully separate)

### 7.1 Information Architecture

```
Current:  Dashboard (all DBs) → /databases/new → /databases/{name}
Proposed: Dashboard → Provision (engine chooser) → Engine context (Mongo | Postgres) — fully separate
         /mongo/databases/{name}  (Mongo only)
         /postgres/databases/{name} (Postgres only)
         Same name allowed: /mongo/databases/myapp and /postgres/databases/myapp coexist
```

**Route table (enterprise — prefix mandatory, no ambiguity):**

| Route | Purpose |
|---|---|
| `GET /` | Dashboard overview — two separate stat cards (Mongo count / Postgres count) + two tables + recent activity per engine |
| `GET /provision` | **Provision tab** — two cards: `MongoDB` / `PostgreSQL` (landing for "New database") |
| `GET /provision/mongo` | Mongo form (`engine=MONGO`) |
| `GET /provision/postgres` | Postgres form (`engine=POSTGRES`, PG validator) |
| `POST /mongo/databases` | Provision Mongo DB |
| `POST /postgres/databases` | Provision Postgres DB |
| `GET /mongo` | Mongo engine home — lists only Mongo DBs |
| `GET /postgres` | Postgres engine home — lists only Postgres DBs |
| `GET /mongo/databases/{name}` | Mongo detail (collections, stats, backup, users) |
| `GET /postgres/databases/{name}` | Postgres detail (tables, stats, users) — collections hidden Phase 1 |
| `GET /mongo/databases/{name}/reset` | Mongo reset password |
| `GET /postgres/databases/{name}/reset` | Postgres reset password |
| `GET /mongo-express` | Mongo explorer proxy |
| `GET /adminer` | Postgres explorer proxy |
| `GET /databases/{name}` | Legacy — 301 redirect to `/{engine}/databases/{name}` via metadata, or 404 if not found |

### 7.2 Sidebar — Two Modes (fully separate)

**Mode A — Root (no engine selected):**

```
┌─ Sidebar ──────────────────┐
│ [icon] DB Manager          │
│ Main                       │
│  ○ Dashboard               │
│  ○ Activity  ○ Health      │
│  ○ Monitor                 │
│ Provision  ← NEW           │
│  ┌─ MongoDB ─┐ ┌─ Postgres┐│  ← two cards, click → enter engine context
│  │ 12 DBs    │ │ 3 DBs    ││
│  └───────────┘ └──────────┘│
│ Explorers                  │
│  ↗ Mongo Express           │
│  ↗ Adminer (Postgres)      │
│ Quick Actions              │
│  + New database → /provision│
└────────────────────────────┘
```

**Mode B — Engine context (e.g. `/mongo/**` or `/postgres/**`):**

```
┌─ Sidebar ──────────────────┐
│ ← Back to Dashboard  ← NEW │  ← sticky top, th:href="@{/}" + history.back() fallback
│ ─────────────────────────  │
│ ● MongoDB  (or PostgreSQL) │  ← engine header with icon + badge count
│  ○ Databases               │  ← /mongo or /postgres
│  ○ Stats                   │  ← engine-wide stats
│  ○ Users                   │  ← aggregated users
│  ───────────────────────── │
│  Explorer                  │
│   ↗ Mongo Express          │  ← only relevant explorer per engine
│   ↗ Adminer                │     (Mongo → mongo-express, PG → adminer)
│  ───────────────────────── │
│  + New Mongo DB            │  ← contextual create → /provision/mongo
└────────────────────────────┘
```

**Back button spec:**

- Visual: `btn btn-sm btn-outline-light w-100` with `<i class="bi bi-arrow-left">` + "Back" — placed above engine header, `position: sticky; top: 0` inside `sidebar-nav`.
- Behavior: `th:href="@{/}"` primary; `onclick="if(history.length>1){history.back();return false}"` as progressive enhancement. Never `javascript:history.back()` alone.
- Active state: engine link gets `.active` (existing `site.css:175-195` green inset) — reuse.

### 7.3 Provision Tab (`/provision`)

- Layout: `container-xxl` with two equal cards (Bootstrap `row g-4`):
  - **MongoDB card:** `bi-database` icon, count `mongoCount`, "Provision MongoDB" button → `/provision/mongo`, hint "Collections, documents, JSON export"
  - **PostgreSQL card:** `bi-server` icon, count `pgCount`, "Provision PostgreSQL" button → `/provision/postgres`, hint "Tables, rows, SQL"
- Each card shows `engine reachable` dot (`live-dot` / `live-dot-off` from `site.css:813-828`) via `HealthService` PG ping.
- Forms: reuse `provision.html:34-80` — separate templates `provision-mongo.html` / `provision-postgres.html` with engine-specific hints: Mongo `[A-Za-z0-9_-]+`, PG `^[a-z_][a-z0-9_]*$` max 63 lowercased.

### 7.4 Templates to Add/Change

| File | Change |
|---|---|
| `fragments/nav.html` | Add `nav(engine)` param: `th:fragment="nav(page, engine)"`. Branch `th:if="${engine==null}"` (root) vs `th:if="${engine!=null}"` (engine context). Add back button, engine header, contextual links. Keep `head(title)` unchanged. |
| `index.html` | **Split into two separate tables** — `mongoDatabases` + `postgresDatabases` with engine badges, no mixed list. Add Provision CTA cards at top. |
| `provision.html` | Becomes chooser (`/provision`). Extract form to `provision-form.html` with `th:object="${form}"` + `engineType` hidden. |
| `provision-mongo.html` / `provision-postgres.html` | Thin wrappers around `provision-form.html` with engine-specific hints. |
| `database.html` | Add `engine` badge, conditional sections: `th:if="${database.engineType=='MONGO'}"` show Collections, `th:if="${database.engineType=='POSTGRES'}"` show Tables (Phase 3). Connection string label: `MongoDB URI` vs `PostgreSQL URI` with `sslmode` hint. |
| `engine-home.html` (new) | Lists DBs for one engine — reuse `index.html:82-175` table, filtered per engine. |
| `site.css` | Add `.sidebar-back`, `.engine-header`, `.provision-card`, `.provision-card-mongo/.provision-card-pg` (border-left accent), `.engine-badge-mongo/.engine-badge-pg`. Reuse existing tokens (`--sidebar-*`, `--bs-primary`). |

### 7.5 CSS Delta (minimal, reuse tokens)

```css
.sidebar-back { margin: 0.5rem 0.85rem; }
.engine-header { padding: 0.75rem 0.85rem 0.25rem; display:flex; align-items:center; gap:0.6rem; color:#fff; font-weight:700; }
.engine-header i { font-size:1.2rem; }
.provision-card { border-left: 4px solid var(--bs-primary); transition: transform .15s; }
.provision-card:hover { transform: translateY(-2px); }
.provision-card-pg { border-left-color: #336791; } /* PG blue */
.engine-badge-mongo { background: rgb(0 104 74 / 0.12); color:#00684a; }
.engine-badge-pg { background: rgb(51 103 145 / 0.12); color:#336791; }
```

### 7.6 Controller / DTO Delta (UI wiring)

- `DashboardController:27` — add `mongoCount`, `postgresCount`, `mongoReachable`, `postgresReachable` to model. No mixed list.
- `DatabaseController` → split into `MongoController` + `PostgresController` (or single with `/{engine}` prefix). Inject `DatabaseEngine` map to resolve.
- `CreateDatabaseForm` — add `DatabaseEngineType engineType` (`@NotNull`).
- `DatabaseInfo` — add `DatabaseEngineType engineType`.
- `AuthModelAdvice` — expose `engine` model attr for nav active state.

### 7.7 Responsive

- `991px` collapsed sidebar (`site.css:1024-1057`) — back button collapses to icon-only (`<i class="bi bi-arrow-left">`), engine header hides text, provision cards stack `col-12`.
- `767px` top bar (`site.css:1059-1127`) — back button stays left of brand, provision cards single column.

---

## 8. Risks & Mitigations (enterprise)

- **SQL injection via db/user names** → strict validator + `quoteIdentifier()` + parameterized passwords.
- **Orphaned roles on failure** → best-effort cleanup (`try dropUser` in catch) per engine.
- **DROP DATABASE blocked by active connections** → `pg_terminate_backend` before drop (exclude own pid).
- **CREATE DATABASE in transaction** → never annotate PG DDL with `@Transactional`; use auto-commit.
- **Same name across engines** → **allowed** via composite key `engine:dbName`; routes are prefixed so no collision. Lock key is `engine:dbName`.
- **Password storage** — same plaintext risk as Mongo (`storedPassword`); enterprise: encrypt at rest with AES-256-GCM + KEK in Phase 4, document that `mongodb_admin` must be protected. PG18 warns on MD5 — use `scram-sha-256` (default).
- **Existing data** — old `ManagedDatabase` docs lack `engineType`; read as `MONGO` default, write back on next update.
- **PGDATA volume for PG18** → mount `/var/lib/postgresql` not `/var/lib/postgresql/data` or data lost on recreate.
- **TLS** — enterprise: `hostssl` + `scram-sha-256` in `pg_hba.conf`, `sslmode=require` minimum, `verify-full` with CA. Document `POSTGRES_PUBLIC_SSLMODE`.
- **Least privilege** — not `ALL`; use `CONNECT` + `USAGE,CREATE` + `ALTER DEFAULT PRIVILEGES`. Revoke `PUBLIC CREATE` on `public` schema.
- **Image size on 1GB VPS** — pgAdmin ~300MB pushes over `650m` mongo cap; Adminer 41.6 MB stays under. Enterprise can still swap.

---

## 9. Testing Strategy

- Unit: `DatabaseNameValidator` (PG vs Mongo rules), `PostgresDatabaseRepository` with mocked `JdbcTemplate`, `ProvisioningService` with fake engines, composite id handling.
- Integration: Testcontainers `PostgreSQLContainer("postgres:18.6-alpine")` + `MongoDBContainer` — provision/reset/delete lifecycle for each engine, **same name in both engines** (e.g. `myapp` in MONGO and POSTGRES), connection string format with `sslmode`, least-privilege grants verification (`has_database_privilege`, `\dp`).
- Controller slice: `@WebMvcTest` for `MongoController` + `PostgresController` with `engineType` param, prefix routes, legacy redirect.
- Verify `CREATE DATABASE` outside transaction — test that repository method is not wrapped in `@Transactional`.

---

## 10. Phased Roadmap

**Phase 1 — Core provisioning + Adminer proxy (1-2 days):** pom/compose/config (with PG18 PGDATA fix), `DatabaseEngine` abstraction, `PostgresDatabaseRepository` (no transaction, least-privilege grants), `ManagedDatabase` composite id `engine:dbName`, `ProvisioningService` dual-engine with separate lists, prefix routes `/mongo/databases/{name}` + `/postgres/databases/{name}`, form + dashboard separate tables, provision-first nav with back button. Same name allowed.

**Phase 2 — Polish:** `DatabaseLockRegistry` composite key, health check (`SELECT 1` for Postgres) with `ServerHealth` per engine, virtual-thread stats, docs/README update, TLS `sslmode` handling.

**Phase 3 — Postgres exploration (optional):** `listTables`, `getTableStats` via `information_schema` + `pg_stat_user_tables`, paginated `SELECT * LIMIT/OFFSET`, export as JSON. Separate from Mongo collections.

**Phase 4 — Hardening (enterprise):** encrypt `storedPassword` (AES-256-GCM + KEK), `pg_dump` backup, rate-limit per engine, metrics, `hostssl` + `scram-sha-256` hardening, `verify-full` CA support.

---

## 11. Open Questions — Resolved

1. Same DB name across engines allowed? **Yes** — composite key `engine:dbName`, prefix routes, lock key `engine:dbName`.
2. URL scheme: **Prefix mandatory** — `/mongo/databases/{name}` + `/postgres/databases/{name}`. Legacy `/databases/{name}` 301 redirects via metadata.
3. Postgres privileges: **Least-privilege** — `CONNECT` on DB + `USAGE,CREATE` on `public` schema + `ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES/SEQUENCES` (not `ALL ON DATABASE`). Revoke `PUBLIC CREATE` on `public`.
4. Public host: **Separate** — `POSTGRES_PUBLIC_HOST` + `POSTGRES_PUBLIC_TLS` + `POSTGRES_PUBLIC_SSLMODE` (require/verify-full), keep `MONGODB_PUBLIC_HOST` separate. Connection strings include `sslmode` + `application_name`.
5. Explorer: **Adminer `6.0.1-standalone` default** (41.6 MB, enterprise can swap to `dpage/pgadmin4` via one-line compose).
6. Metadata in Postgres later? **Keep in Mongo** for now — separate engines, no migration.

Say `go Phase 1` and I'll implement it in the order above.

---

## 12. Research Sources (2026-08-27)

- Spring Boot 4.1.1 Reference — `docs.spring.io/spring-boot/reference/` (Data, SQL Databases, Docker Compose, Testcontainers)
- Spring Boot 4.1 Release Notes — `github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes`
- PostgreSQL 18.6 Docs — `postgresql.org/docs/18/sql-createdatabase.html`, `sql-createrole.html`, `sql-grant.html`, `ddl-priv.html`, `sql-alterdefaultprivileges.html`, `auth-pg-hba-conf.html`, `libpq-connect.html`, `release-18.html`
- Docker Hub — `hub.docker.com/_/postgres` (tags 18.6, 18.6-alpine, PGDATA change), `hub.docker.com/_/adminer` (tags 6.0.1-standalone)
- Testcontainers — `java.testcontainers.org/modules/databases/postgres/`
- Adminer — `adminer.org` (v6.0.1, 2026-08-14)
- OWASP — `cheatsheetseries.owasp.org` (Database Security, Cryptographic Storage)
