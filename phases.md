# Phases — Enterprise Dual-Engine DB Manager (MongoDB + PostgreSQL 18)

> Source: `postgres_ingt_plan.md` (research 2026-08-27, PG 18.6, Boot 4.1.1, Adminer 6.0.1).  
> Decisions locked: **same name allowed** (`myapp` in both), **prefix routes** `/mongo/databases/{name}` + `/postgres/databases/{name}`, **fully separate** engines, **enterprise-grade** defaults.  
> Stack: Java 25, Spring Boot 4.1.0, `postgres:18.6-alpine`, `adminer:6.0.1-standalone`, `postgresql:42.7.5`.

---

## Table of Contents

1. [Conventions](#0-conventions)
2. [Phase 1 — Core Provisioning + Infra + Provision-First Nav](#1-phase-1--core-provisioning--infra--provision-first-nav-1-2-days)
3. [Phase 2 — Enterprise Separation & Reliability](#2-phase-2--enterprise-separation--reliability-1-day)
4. [Phase 3 — Engine-Specific Exploration & Observability](#3-phase-3--engine-specific-exploration--observability-2-days)
5. [Phase 4 — Hardening & Compliance](#4-phase-4--hardening--compliance-2-days)
6. [Cross-Phase Appendix](#5-cross-phase-appendix)

---

## 0. Conventions

### Identity

- **Composite id:** `ManagedDatabase.id = engine + ":" + dbName` e.g. `MONGO:myapp`, `POSTGRES:myapp`. Allows same name across engines.
- **Unique index:** `provisioned_databases` compound unique on `(engineType, dbName)` + single index on `engineType`.
- **Lock key:** `DatabaseLockRegistry` key = `engine:dbName` (not bare `dbName`) — concurrent `myapp` in both engines never blocks each other.
- **Enum:** `model/DatabaseEngineType.java` — `MONGO, POSTGRES`. Existing docs without `engineType` read as `MONGO` (backward compat).

### Separation Rule

No mixed lists. Every read/write is engine-scoped:

- `listDatabases(engine)` not `listDatabases()` merged
- `getDatabase(engine, dbName)` not `getDatabase(dbName)` probing both
- Dashboard shows **two separate tables/cards**, not one merged table
- Audit `AuditEvent` carries `engineType`
- Health `ServerHealth` carries `mongoReachable` + `postgresReachable` separately

### Routes (prefix mandatory)

| Route | Engine |
|---|---|
| `GET /` | Dashboard — two engine sections |
| `GET /provision` | Chooser — MongoDB card + PostgreSQL card |
| `GET /provision/mongo` | Mongo form |
| `GET /provision/postgres` | Postgres form |
| `POST /mongo/databases` | Provision Mongo |
| `POST /postgres/databases` | Provision Postgres |
| `GET /mongo` | Mongo home — only Mongo DBs |
| `GET /postgres` | Postgres home — only Postgres DBs |
| `GET /mongo/databases/{name}` | Mongo detail |
| `GET /postgres/databases/{name}` | Postgres detail |
| `POST /mongo/databases/{name}/reset` | Mongo reset |
| `POST /postgres/databases/{name}/reset` | Postgres reset |
| `POST /mongo/databases/{name}/delete` | Mongo delete |
| `POST /postgres/databases/{name}/delete` | Postgres delete |
| `GET /mongo-express` | Mongo explorer proxy (existing) |
| `GET /adminer` | Postgres explorer proxy (new) |
| `GET /databases/{name}` | Legacy — 301 to `/{engine}/databases/{name}` via metadata lookup |

### Communication with Client (enterprise choice)

**Why this choice:** OWASP + PG libpq docs. Separate public hosts per engine, TLS explicit, `application_name` for audit, least-privilege grants.

- **MongoDB URI:** `mongodb://user:pass@host/db?authSource=db` + `&tls=true` when `MONGODB_PUBLIC_TLS=true`. Host from `app.mongo-public-host` or derived from `spring.mongodb.uri`.
- **PostgreSQL URI:** `postgresql://user:pass@host:5432/db?sslmode=require&application_name=mongodbserver` — `sslmode` from `app.postgres.public-sslmode` (`require` default, `verify-full` with CA). Host from `app.postgres.public-host` or `spring.datasource` host. `uriEncode` reused for user/pass.
- **JDBC alternative (Java clients):** `jdbc:postgresql://host:5432/db?sslmode=require&ApplicationName=mongodbserver`
- **Public host separation:** `MONGODB_PUBLIC_HOST` / `MONGODB_PUBLIC_TLS` vs `POSTGRES_PUBLIC_HOST` / `POSTGRES_PUBLIC_TLS` / `POSTGRES_PUBLIC_SSLMODE` — never shared. Empty = derived from internal URI (dev) or `127.0.0.1:9812/9813`.
- **TLS enforcement:** `hostssl` + `scram-sha-256` in `pg_hba.conf` (Phase 4), `sslmode=require` minimum in issued strings. `verify-full` when CA available.

---

## 1. Phase 1 — Core Provisioning + Infra + Provision-First Nav (1-2 days)

### Objective

Provision **either** Mongo or Postgres with a dedicated per-DB user, show a dialable connection string, support reset-password and delete. Both engines coexist. Add Adminer proxied like mongo-express. Provision-first nav with back button. Same name allowed.

### Non-Goals

- Exploration (tables/collections), stats, backup/restore, `pg_dump`
- Health per engine (basic ping only)
- Encryption at rest, rate-limit per engine

### 1.1 Build / Infra

**`pom.xml` — add (Boot 4.1.1 BOM manages versions, no version needed):**

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

Keep `spring-boot-starter-data-mongodb`. Verify `mvn dependency:tree` shows `postgresql:42.7.5`.

**`compose.yaml` — add services (PG18 PGDATA fix is critical):**

```yaml
  postgres:
    image: postgres:18.6-alpine
    container_name: mongodbserver-postgres
    restart: unless-stopped
    ports: ["127.0.0.1:9813:5432"]
    environment:
      POSTGRES_USER: ${POSTGRES_ROOT_USER:-root}
      POSTGRES_PASSWORD: ${POSTGRES_ROOT_PASSWORD:-root}
      PGDATA: /var/lib/postgresql/18/docker  # PG18 path, not /var/lib/postgresql/data
    volumes: [postgres-data:/var/lib/postgresql]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5

  adminer:
    image: adminer:6.0.1-standalone
    container_name: mongodbserver-adminer
    restart: unless-stopped
    ports: ["127.0.0.1:9815:8080"]  # loopback only, like mongo-express:38
    environment: {ADMINER_DEFAULT_SERVER: postgres}
    depends_on: [postgres]

volumes: {mongo-data:, postgres-data:}
```

**`.env.example` — add:**

```
POSTGRES_ROOT_USER=root
POSTGRES_ROOT_PASSWORD=change-me-now
# POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable
# POSTGRES_PUBLIC_HOST=postgres.example.com
# POSTGRES_PUBLIC_TLS=false
# POSTGRES_PUBLIC_SSLMODE=require
# ADMINER_BASE_URL=http://127.0.0.1:9815
```

**`src/main/resources/application.yml` — add:**

```yaml
app.postgres.enabled: ${POSTGRES_ENABLED:false}
app.postgres.uri: ${POSTGRES_URI:jdbc:postgresql://127.0.0.1:9813/postgres}
app.postgres.public-host: ${POSTGRES_PUBLIC_HOST:}
app.postgres.public-tls: ${POSTGRES_PUBLIC_TLS:false}
app.postgres.public-sslmode: ${POSTGRES_PUBLIC_SSLMODE:require}
app.adminer.base-url: ${ADMINER_BASE_URL:http://127.0.0.1:9815}
spring.datasource:  # auto-configured when app.postgres.enabled=true
  url: ${app.postgres.uri}
  username: ${POSTGRES_ROOT_USER:root}
  password: ${POSTGRES_ROOT_PASSWORD:root}
  hikari.maximum-pool-size: 5
  hikari.minimum-idle: 1
```

### 1.2 Domain / DTO

| File | Change |
|---|---|
| `model/DatabaseEngineType.java` | **New enum** `MONGO, POSTGRES` |
| `model/ManagedDatabase.java:18-51` | Add `DatabaseEngineType engineType` (default `MONGO` for old docs), change `id` to `engine + ":" + dbName` in constructor, add getter/setter, update `equals/hashCode` if present. Keep `dbName`, `userName`, `roles`, `storedPassword`, `createdAt`, `updatedAt`, `lastPasswordResetAt`. |
| `dto/DatabaseInfo.java` | Add `DatabaseEngineType engineType` field (record component or getter). |
| `dto/CreateDatabaseForm.java` | Add `DatabaseEngineType engineType` with `@NotNull`, keep `dbName`, `userName`, `password` with existing validation. |
| `dto/DatabaseUser.java` | No change — reuse (PG roles = `CONNECT, CREATE` etc). |
| `model/AuditEvent.java` | Add `DatabaseEngineType engineType` field. |

**Migration note:** `ManagedDatabaseRepository` — existing docs without `engineType` deserialize as `null` → treat as `MONGO` in service layer, write back on next save.

### 1.3 Repository Layer

**`repository/PostgresDatabaseRepository.java` — new, `JdbcTemplate` based, NO `@Transactional`:**

- `quoteIdentifier(String)` → `"` + `name.replace("\"","\"\"")` + `"` — never interpolate identifiers.
- `listDatabaseNames()` → `SELECT datname FROM pg_database WHERE datistemplate=false AND datname NOT IN ('postgres','template0','template1')`
- `databaseExists(db)` → `SELECT 1 FROM pg_database WHERE datname=?`
- `getDatabaseSize(db)` → `SELECT pg_database_size(?)`
- `createDatabase(db, owner)` → `CREATE DATABASE "db" OWNER "owner" TEMPLATE template0 ENCODING 'UTF8'` — **outside transaction** (PG docs: cannot run in transaction block). Use `quoteIdentifier` for both.
- `dropDatabase(db)` → `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=? AND pid <> pg_backend_pid()` then `DROP DATABASE IF EXISTS "db"` — also outside transaction.
- `createUser(db, user, pass)` → `CREATE ROLE "user" WITH LOGIN PASSWORD ?` (param `pass`) + `GRANT CONNECT ON DATABASE "db" TO "user"` + connect to `db` and `GRANT USAGE, CREATE ON SCHEMA public TO "user"` + `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO "user"` + `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO "user"` — least-privilege (OWASP), future objects auto-granted. `password_encryption=scram-sha-256` default in PG18.
- `updateUserPassword(db, user, pass)` → `ALTER ROLE "user" WITH PASSWORD ?`
- `dropUser(db, user)` → `REVOKE ALL ON DATABASE "db" FROM "user"` + `REVOKE ALL ON SCHEMA public FROM "user"` then `DROP ROLE IF EXISTS "user"`
- `getUsers(db)` → `SELECT usename FROM pg_user WHERE has_database_privilege(usename, db, 'CONNECT')`
- `listTables(db)` — stub, throws `UnsupportedOperationException` Phase 1.

**`repository/MongoDatabaseRepository.java`** — no logic change; will be wrapped as `MongoDatabaseEngine`.

### 1.4 Service Layer

**`service/DatabaseEngine.java` — new interface:**

```java
public interface DatabaseEngine {
  DatabaseEngineType type();
  void createUser(String db, String user, String pass);
  void createDatabase(String db, String owner);
  void dropDatabase(String db);
  void dropUser(String db, String user);
  void updateUserPassword(String db, String user, String pass);
  boolean databaseExists(String db);
  List<String> listDatabaseNames();
  Map<String,Long> getDatabaseSizes();
  String buildConnectionString(String user, String pass, String db);
  List<Document> getUsers(String db); // or List<DatabaseUser>
}
```

- **`service/MongoDatabaseEngine.java`** — delegates to `MongoDatabaseRepository`, implements `buildConnectionString` via existing `uriEncode` + `resolveConnectionHost()` + `resolveConnectionTls()` (from `ProvisioningService:397-423`).
- **`service/PostgresDatabaseEngine.java`** — delegates to `PostgresDatabaseRepository`, builds `postgresql://user:pass@host:5432/db?sslmode=require&application_name=mongodbserver` (or `verify-full` when `public-sslmode=verify-full`). Reuse `uriEncode` for user/pass. Host from `app.postgres.public-host` or parsed from `spring.datasource.url`.
- **`service/ProvisioningService.java:56-499` — refactor to keep engines separate:**
  - Inject `Map<DatabaseEngineType, DatabaseEngine> engines` + `DatabaseNameValidator`.
  - `provision(form)` → `engines.get(form.engineType()).createUser/createDatabase`, store `engineType` in `ManagedDatabase` with composite id `engine:dbName`, `buildConnectionString` via engine. No `@Transactional` on PG path. Best-effort cleanup on failure (same as Mongo `dropUser` in catch).
  - `resetPassword(engine, dbName, form)` → lookup by `engine:dbName`, delegate `updateUserPassword`.
  - `delete(engine, dbName)` → lookup by `engine:dbName`, delegate `dropDatabase/dropUser` (terminate backends first for PG).
  - `listDatabases(engine)` → per-engine list (no merge). Dashboard calls both.
  - `getDatabase(engine, dbName)` → find by composite id.
  - `listUsers/revokeUser` → per-engine.
  - `createCollection/dropCollection` → only for `MONGO`; PG throws `NameNotAllowedException("Collections not supported for PostgreSQL")`.
  - Lock key: `engine + ":" + dbName` — allows `myapp` in both engines concurrently.
  - `resolveConnectionHost()` split into `resolveMongoHost()` + `resolvePostgresHost()`.
- **`service/MongoNameValidator.java` → `service/DatabaseNameValidator.java`** — rename or add `validatePostgresDatabaseName`/`validatePostgresUserName` (PG: `^[a-z_][a-z0-9_]*$`, max 63, lowercased, must not start with digit). Mongo keeps `[A-Za-z0-9_-]+` max 64.
- **`service/ExplorationService.java`, `StatisticsService.java`, `BackupService.java`, `HealthService.java`** — Phase 1: add `if (engine==POSTGRES) throw/return empty` at top. No PG logic yet.

### 1.5 Controller / View

- **`controller/ProvisionController.java` — new:**
  - `GET /provision` → chooser (two cards)
  - `GET /provision/mongo` → `provision-mongo.html` (`engine=MONGO` hidden)
  - `GET /provision/postgres` → `provision-postgres.html` (`engine=POSTGRES` hidden)
- **`controller/MongoController.java` — new + `controller/PostgresController.java` — new** (or single `DatabaseController` with `/{engine}` prefix):
  - `GET /mongo` → `engine-home.html` with `engine=MONGO`
  - `GET /postgres` → `engine-home.html` with `engine=POSTGRES`
  - `GET /mongo/databases/{name}` → `database.html` with `engine=MONGO`
  - `GET /postgres/databases/{name}` → `database.html` with `engine=POSTGRES`
  - `POST /mongo/databases` + `POST /postgres/databases` → provision
  - `POST /mongo/databases/{name}/reset` + `POST /postgres/databases/{name}/reset`
  - `POST /mongo/databases/{name}/delete` + `POST /postgres/databases/{name}/delete`
  - Legacy `GET /databases/{name}` → 301 redirect to `/{engine}/databases/{name}` via `managedDatabaseRepository.findByDbName` (if one match) or 404 if ambiguous.
- **`controller/DashboardController.java:27` — update:** add `mongoCount`, `postgresCount`, `mongoReachable`, `postgresReachable` to model. No mixed `databases` list — two separate lists `mongoDatabases` + `postgresDatabases`.
- **`controller/DatabaseController.java:24-165` — deprecate or split:** keep for legacy redirect only, or remove after prefix migration.

**Thymeleaf:**

| File | Change |
|---|---|
| `fragments/nav.html:18-53` | Add `nav(page, engine)` param: `th:fragment="nav(page, engine)"`. Branch `th:if="${engine==null}"` (root) vs `th:if="${engine!=null}"` (engine context). Add back button `th:href="@{/}"` + `onclick="if(history.length>1){history.back();return false}"`, engine header `● MongoDB` / `● PostgreSQL` with count badge, contextual links (`/mongo` vs `/postgres`), relevant explorer only. Keep `head(title)` unchanged. |
| `index.html` | Split `databases` into `mongoDatabases` + `postgresDatabases` — two separate tables with `engine` badge (`engine-badge-mongo` green, `engine-badge-pg` blue). Add Provision CTA cards at top (two cards with `live-dot` reachable). |
| `provision.html` | Becomes chooser (`/provision`) — two cards `MongoDB` / `PostgreSQL` with counts + reachable dot + button to `/provision/mongo` / `/provision/postgres`. |
| `provision-mongo.html` / `provision-postgres.html` | Thin wrappers around `provision-form.html` fragment with `th:object="${form}"` + hidden `engineType`. PG hints: `^[a-z_][a-z0-9_]*$`, max 63, lowercased. |
| `provision-form.html` | **New fragment** — extracted from `provision.html:34-80` with `engineType` hidden input. |
| `database.html` | Add `engine` badge, conditional: `th:if="${database.engineType=='MONGO'}"` show Collections, `th:if="${database.engineType=='POSTGRES'}"` show Tables placeholder (Phase 3). Connection string label: `MongoDB URI` vs `PostgreSQL URI` with `sslmode` hint. |
| `engine-home.html` | **New** — lists DBs for one engine, reuses `index.html:82-175` table filtered per engine. |

**`site.css` — add (reuse tokens `--sidebar-*`, `--bs-primary`):**

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

### 1.6 Config

- **`config/PostgresConfig.java` — new:** `@ConditionalOnProperty("app.postgres.enabled", havingValue="true")` creates `DataSource` + `JdbcTemplate` + `PostgresDatabaseRepository` + `PostgresDatabaseEngine` beans. Hikari `maximumPoolSize=5`, `minimumIdle=1`. No `@Transactional` on DDL beans.
- **`config/AdminerProxyFilter.java` — new:** clone of `MongoExpressProxyFilter:36` — `PROXY_PREFIX="/adminer"`, `targetBase=http://127.0.0.1:9815`, same `NON_FORWARDED_HEADERS`, `HttpClient` (5s connect, 60s timeout), `Location` rewrite, 502/400 handling.
- **`config/SecurityConfig.java:34-55` — update:** add `.requestMatchers("/mongo/**").hasRole("ADMIN")` for POST/DELETE, `.requestMatchers("/postgres/**").hasRole("ADMIN")` for POST/DELETE, `.requestMatchers("/adminer/**").hasRole("ADMIN")`, `.csrf(csrf->csrf.ignoringRequestMatchers("/mongo-express/**","/adminer/**"))`.
- **`config/MongoIndexInitializer.java` — update:** add compound unique index on `provisioned_databases` `(engineType, dbName)` + index on `engineType`. Handle existing docs without `engineType`.

### 1.7 DDL (PG 18.6 — least-privilege)

```sql
-- provision (outside transaction block — PG docs)
CREATE ROLE "myapp_user" WITH LOGIN PASSWORD '...' ; -- scram-sha-256 default
CREATE DATABASE "myapp" OWNER "myapp_user" TEMPLATE template0 ENCODING 'UTF8';
GRANT CONNECT ON DATABASE "myapp" TO "myapp_user";
-- connect to "myapp" then:
GRANT USAGE, CREATE ON SCHEMA public TO "myapp_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO "myapp_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO "myapp_user";

-- reset (can be in transaction)
ALTER ROLE "myapp_user" WITH PASSWORD '...';

-- delete (DROP DATABASE cannot be in transaction)
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='myapp' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "myapp";
REVOKE ALL ON DATABASE "myapp" FROM "myapp_user";
DROP ROLE IF EXISTS "myapp_user";
```

### 1.8 Testing

- **Unit:** `DatabaseNameValidator` (PG `^[a-z_][a-z0-9_]*$` max 63 vs Mongo `[A-Za-z0-9_-]+`), `PostgresDatabaseRepository` with mocked `JdbcTemplate`, `ProvisioningService` with fake engines, composite id `engine:dbName`.
- **Integration:** Testcontainers `PostgreSQLContainer("postgres:18.6-alpine")` + `MongoDBContainer` — provision/reset/delete lifecycle per engine, **same name in both engines** (`myapp` in MONGO and POSTGRES), connection string format with `sslmode`, least-privilege grants verification (`has_database_privilege`).
- **Controller slice:** `@WebMvcTest(MongoController)` + `@WebMvcTest(PostgresController)` with `engineType` param, prefix routes, legacy redirect.
- **Verify:** `CREATE DATABASE` outside transaction — test that repository method is not wrapped in `@Transactional`.

### 1.9 Acceptance Criteria

- [x] `POST /mongo/databases` and `POST /postgres/databases` both provision with same `dbName` (e.g. `myapp`) — two separate `ManagedDatabase` docs `MONGO:myapp` + `POSTGRES:myapp`.
- [x] Connection strings are dialable: Mongo `mongodb://...?authSource=db`, Postgres `postgresql://...?sslmode=require&application_name=omnidb`.
- [x] Reset/delete work per engine without affecting the other.
- [x] `/provision` chooser shows two cards with counts + reachable dots.
- [x] Sidebar has back button `← Back to Dashboard` in engine context, `th:href="@{/}"` + `history.back()` fallback.
- [x] `/adminer` proxied behind `ADMIN` login, 502 when Adminer down, loopback only.
- [x] No mixed lists — dashboard shows two separate tables.

---

## 2. Phase 2 — Enterprise Separation & Reliability (1 day)

### Objective

Make separation bulletproof: composite locks, per-engine health, validation hardening, dashboard polish, virtual threads. No cross-engine leakage.

### 2.1 Changes

| Area | File | Change |
|---|---|---|
| **Locks** | `service/DatabaseLockRegistry.java` | Key = `engine + ":" + dbName`. Add `withLock(DatabaseEngineType, String, Supplier)` overload. Existing `withLock(String)` delegates to `MONGO` for backward compat or removed. |
| **Health** | `service/HealthService.java` | Add `postgresReachable` via `SELECT 1` on `JdbcTemplate` (when `app.postgres.enabled`). `ServerHealth` gets `mongoReachable` + `postgresReachable` + `postgresVersion`. Dashboard shows two dots (`live-dot` / `live-dot-off` from `site.css:813-828`). |
| **Validation** | `service/DatabaseNameValidator.java` | Enforce PG max 63, lowercased, `^[a-z_][a-z0-9_]*$`, must not start with digit. Mongo keeps 64, `[A-Za-z0-9_-]+`. Add `validateCollectionName` only for MONGO. |
| **Stats** | `service/StatisticsService.java:88` | Replace `newFixedThreadPool(16)` with `Executors.newVirtualThreadPerTaskExecutor()` (Java 25) for `collStats` + `pg_stat` fan-out. Add `getPostgresStats(db)` stub returning empty Phase 2, full in Phase 3. |
| **Dashboard** | `controller/DashboardController.java` | Add `mongoCount`, `postgresCount`, `mongoReachable`, `postgresReachable` to model. No mixed list. |
| **Audit** | `model/AuditEvent.java` + `service/ProvisioningService.java:431` | Add `engineType` to `audit()` calls — `PROVISION`, `RESET_PASSWORD`, `DELETE`, `REVOKE_USER` all carry engine. `activity.html` filter by engine. |
| **Security** | `config/SecurityConfig.java` | Tighten: `GET /mongo/**` + `GET /postgres/**` authenticated, `POST /mongo/**` + `POST /postgres/**` hasRole ADMIN. Keep `/mongo-express/**` + `/adminer/**` ADMIN only. |
| **Docs** | `README.md` | Update architecture diagram — two engines, prefix routes, composite id. |

### 2.2 Testing

- **Concurrency:** `ProvisioningConcurrencyTest` — concurrent `provision(MONGO, myapp)` + `provision(POSTGRES, myapp)` both succeed (different lock keys). Concurrent `provision(MONGO, myapp)` twice — one fails with `DatabaseAlreadyExistsException`.
- **Health:** `HealthServiceTest` — PG `SELECT 1` success/failure, `postgresReachable` false when `app.postgres.enabled=false`.
- **Validation:** `DatabaseNameValidatorTest` — PG rejects `MyApp`, `123abc`, `a-very-long-name-over-63-chars-...`, accepts `myapp`, `my_app_123`.

### 2.3 Acceptance Criteria

- [x] Same name in both engines never deadlocks or blocks.
- [x] Health page shows `Mongo: ● reachable` + `Postgres: ● reachable` separately.
- [x] Activity log filterable by engine.
- [x] Virtual threads used for stats fan-out (no `newFixedThreadPool`).

---

## 3. Phase 3 — Engine-Specific Exploration & Observability (2 days)

### Objective

Give each engine its own exploration UI: Mongo keeps collections/documents, Postgres gets tables/rows. Separate stats, monitor, and export.

### 3.1 Changes

| Area | File | Change |
|---|---|---|
| **PG Exploration** | `repository/PostgresDatabaseRepository.java` | Add `listTables(db)` → `SELECT table_name FROM information_schema.tables WHERE table_schema='public'`, `getTableStats(db, table)` → `SELECT reltuples, relpages FROM pg_class`, `listRows(db, table, limit, offset)` → `SELECT * FROM "table" LIMIT ? OFFSET ?` (with `quoteIdentifier`), `getTableSize(db, table)` → `SELECT pg_total_relation_size(?)`. |
| **PG Stats** | `service/StatisticsService.java` | Add `getPostgresDatabaseStats(db)` via `pg_stat_user_tables` + `pg_database_size`, `getPostgresTableStats(db, table)` via `pg_stat_user_tables`. Keep Mongo `collStats`/`dbStats` separate. |
| **PG Monitor** | `service/MonitorService.java` | Add `getPostgresMonitorSnapshot()` via `pg_stat_activity`, `pg_stat_database`. Separate SSE streams `/mongo/monitor/stream` + `/postgres/monitor/stream` or single with engine param. |
| **Controllers** | `controller/MongoController.java` + `controller/PostgresController.java` | Mongo: `GET /mongo/databases/{name}/collections` + `GET /mongo/databases/{name}/collections/{coll}` (existing). Postgres: `GET /postgres/databases/{name}/tables` + `GET /postgres/databases/{name}/tables/{table}` + `GET /postgres/databases/{name}/tables/{table}/rows`. |
| **Templates** | `templates/database.html` | Conditional: `th:if="${database.engineType=='MONGO'}"` show Collections tab, `th:if="${database.engineType=='POSTGRES'}"` show Tables tab. Separate `collections.html` vs `tables.html`. |
| **Templates** | `templates/tables.html` + `templates/table-rows.html` | **New** — Postgres tables list + paginated rows (`LIMIT/OFFSET`), reuse `collections.html` styling. |
| **Templates** | `templates/stats.html` | Split into `stats-mongo.html` + `stats-postgres.html` or conditional sections — Mongo `collStats`, Postgres `pg_stat_user_tables`. |
| **Export** | `service/ImportExportService.java` | Add `exportPostgresTable(db, table)` → JSON via `SELECT *` + `Json` util. Keep Mongo `mongodump` separate. |
| **Nav** | `fragments/nav.html` | Engine context shows relevant explorer only: Mongo → `↗ Mongo Express`, Postgres → `↗ Adminer`. |

### 3.2 DDL / Queries

```sql
-- tables
SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';
-- stats
SELECT relname, n_live_tup, n_dead_tup, last_vacuum, last_analyze FROM pg_stat_user_tables WHERE schemaname='public';
-- size
SELECT pg_total_relation_size('public."my_table"');
-- rows (paginated, identifier quoted)
SELECT * FROM "my_table" LIMIT 50 OFFSET 0;
```

### 3.3 Testing

- **Integration:** Testcontainers — create table, insert rows, `listTables` + `listRows` + `getTableStats` per engine. Verify Mongo collections not visible in Postgres context and vice versa.
- **Controller slice:** `@WebMvcTest` for `GET /postgres/databases/{name}/tables` + `GET /postgres/databases/{name}/tables/{table}/rows`.

### 3.4 Acceptance Criteria

- [x] `/mongo/databases/myapp` shows Collections, `/postgres/databases/myapp` shows Tables — never mixed.
- [x] Postgres tables paginated, stats from `pg_stat_user_tables`.
- [x] Monitor shows per-engine activity.

---

## 4. Phase 4 — Hardening & Compliance (2 days)

### Objective

Enterprise hardening: encryption at rest, backup, rate-limit per engine, TLS hardening, audit completeness, metrics.

### 4.1 Changes

| Area | File | Change |
|---|---|---|
| **Encryption** | `model/ManagedDatabase.java` + `service/ProvisioningService.java` | Encrypt `storedPassword` at rest with AES-256-GCM + KEK (from `APP_ENCRYPTION_KEY` env or Vault). Add `EncryptionService` with `encrypt`/`decrypt`, key rotation support. OWASP Crypto cheat sheet: AES/GCM, 256-bit, random IV per record. |
| **Backup** | `service/BackupService.java` | Add `backupPostgresDatabase(db)` via `pg_dump` (exec or `pg_dump` via JDBC `COPY`), `restorePostgresDatabase(db, file)`. Keep Mongo `mongodump` separate. `POST /postgres/databases/{name}/backup` + `/restore`. |
| **Rate Limit** | `config/LoginRateLimitFilter.java` + `config/SecurityConfig.java` | Per-engine rate limit on provision/reset/delete (e.g. 5/min per IP+engine). Add `app.postgres.rate-limit` config. |
| **TLS** | `compose.yaml` + `application.yml` | `postgres` service: `command: ["postgres", "-c", "password_encryption=scram-sha-256", "-c", "ssl=on", "-c", "ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem"]` + `pg_hba.conf` `hostssl all all 0.0.0.0/0 scram-sha-256`. Issued strings use `sslmode=verify-full` when CA available. |
| **Audit** | `service/ProvisioningService.java:431` + `model/AuditEvent.java` | Ensure every lifecycle action (provision, reset, delete, revoke, backup, restore) logs `engineType`, `dbName`, `userName`, `performedBy`, `performedAt`, `clientIp`. `activity.html` filter by engine + event type. |
| **Metrics** | `config/ActuatorConfig.java` | Add `postgres` health indicator (`DataSourceHealthIndicator`), `provisioned_databases` gauge per engine (`MONGO:12`, `POSTGRES:3`). |
| **Secrets** | `.env.example` + `README.md` | Document `APP_ENCRYPTION_KEY` (32-byte base64), `POSTGRES_ROOT_PASSWORD` rotation, `ADMINER` vs `pgAdmin` swap. |
| **Validation** | `service/DatabaseNameValidator.java` | Add reserved names check per engine (Mongo: `admin, local, config`; Postgres: `postgres, template0, template1`). |

### 4.2 DDL / Config

```sql
-- revoke public create hardening (PG15+ already, but explicit)
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
-- verify grants
SELECT grantee, privilege_type FROM information_schema.role_table_grants WHERE table_schema='public';
```

```yaml
# pg_hba.conf (via POSTGRES_INITDB_ARGS or custom config)
hostssl all all 0.0.0.0/0 scram-sha-256
host all all 127.0.0.1/32 trust  # for healthcheck pg_isready
```

### 4.3 Testing

- **Encryption:** `EncryptionServiceTest` — encrypt/decrypt round-trip, key rotation, wrong key fails.
- **Backup:** Testcontainers — `pg_dump` + restore round-trip, verify data integrity.
- **Rate Limit:** `LoginRateLimiterTest` — per-engine limit, burst handling.
- **Security:** Verify `storedPassword` never logged, never returned in API without auth.

### 4.4 Acceptance Criteria

- [x] `storedPassword` encrypted at rest (AES-256-GCM), decryptable for connection string display.
- [x] `pg_dump` backup/restore works per engine (JDBC gzip JSON `formatVersion:1`, `INSERT_BATCH_SIZE=1000`).
- [x] Rate limit per engine enforced (`ProvisionRateLimitFilter` `IP:engine`, `LoginRateLimiter` fixed-window).
- [x] `hostssl` + `scram-sha-256` enforced, `sslmode=verify-full` when CA present (`POSTGRES_PUBLIC_SSLMODE`).
- [x] Audit trail complete per engine, filterable (`AuditEvent.engineType`, `activity.html`).
- [x] No plaintext password in logs or error responses (encrypted `ENC:v1:`, never logged).

---

## 5. Cross-Phase Appendix

### 5.1 File Change Summary (all phases)

| File | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|---|---|---|---|---|
| `pom.xml` | +jdbc +pg driver +testcontainers-pg | — | — | — |
| `compose.yaml` | +postgres +adminer | — | — | +TLS cmd +pg_hba |
| `.env.example` | +POSTGRES_* +ADMINER | — | — | +APP_ENCRYPTION_KEY |
| `application.yml` | +app.postgres.* +spring.datasource | — | — | +rate-limit +sslmode |
| `model/DatabaseEngineType.java` | **New** | — | — | — |
| `model/ManagedDatabase.java` | +engineType, composite id | — | — | +encrypted storedPassword |
| `dto/DatabaseInfo.java` | +engineType | — | — | — |
| `dto/CreateDatabaseForm.java` | +engineType | — | — | — |
| `model/AuditEvent.java` | +engineType | filter by engine | — | +clientIp, event completeness |
| `repository/PostgresDatabaseRepository.java` | **New** | — | +listTables/listRows | — |
| `service/DatabaseEngine.java` | **New** | — | — | — |
| `service/MongoDatabaseEngine.java` | **New** | — | — | — |
| `service/PostgresDatabaseEngine.java` | **New** | — | — | — |
| `service/ProvisioningService.java` | Refactor dual-engine | — | — | +encryption |
| `service/DatabaseNameValidator.java` | Rename + PG rules | Harden | — | +reserved names |
| `service/DatabaseLockRegistry.java` | — | Composite key | — | — |
| `service/HealthService.java` | — | +postgresReachable | — | +DataSourceHealthIndicator |
| `service/StatisticsService.java` | — | Virtual threads | +PG stats | — |
| `service/ExplorationService.java` | Guard PG | — | +PG tables | — |
| `service/BackupService.java` | Guard PG | — | — | +pg_dump |
| `service/EncryptionService.java` | — | — | — | **New** |
| `controller/ProvisionController.java` | **New** | — | — | — |
| `controller/MongoController.java` | **New** | — | +collections | — |
| `controller/PostgresController.java` | **New** | — | +tables/rows | +backup/restore |
| `controller/DashboardController.java` | +mongo/postgres counts | +reachable | — | — |
| `controller/DatabaseController.java` | Legacy redirect | — | — | — |
| `config/PostgresConfig.java` | **New** | — | — | — |
| `config/AdminerProxyFilter.java` | **New** | — | — | — |
| `config/SecurityConfig.java` | +/adminer +/mongo +/postgres | Tighten | — | +rate-limit |
| `config/MongoIndexInitializer.java` | +compound index | — | — | — |
| `fragments/nav.html` | +engine param, back button | — | +explorer per engine | — |
| `index.html` | Split two tables | +reachable dots | — | — |
| `provision.html` | Chooser | — | — | — |
| `provision-mongo.html` | **New** | — | — | — |
| `provision-postgres.html` | **New** | — | — | — |
| `provision-form.html` | **New** fragment | — | — | — |
| `database.html` | +engine badge, conditional | — | +tables tab | — |
| `engine-home.html` | **New** | — | — | — |
| `tables.html` | — | — | **New** | — |
| `table-rows.html` | — | — | **New** | — |
| `site.css` | +sidebar-back etc | — | — | — |

### 5.2 Risks & Mitigations (all phases)

- **SQL injection** → `quoteIdentifier()` + parameterized passwords, strict validator.
- **Orphaned roles** → best-effort `dropUser` in catch per engine.
- **DROP DATABASE blocked** → `pg_terminate_backend` (exclude own pid) before drop.
- **CREATE DATABASE in transaction** → never `@Transactional` on PG DDL, auto-commit.
- **Same name collision** → composite key `engine:dbName`, prefix routes, lock key `engine:dbName` — allowed by design.
- **PGDATA volume** → `/var/lib/postgresql` not `/var/lib/postgresql/data` for PG18.
- **Password plaintext** → Phase 4 AES-256-GCM + KEK, never log.
- **Image size** → Adminer 41.6 MB (not pgAdmin 300 MB) for 1GB VPS.

### 5.3 Testing Strategy (all phases)

- **Unit:** `DatabaseNameValidator` (PG vs Mongo), `PostgresDatabaseRepository` mocked `JdbcTemplate`, `ProvisioningService` fake engines, `EncryptionService`.
- **Integration:** Testcontainers `PostgreSQLContainer("postgres:18.6-alpine")` + `MongoDBContainer` — provision/reset/delete per engine, same name in both, connection strings, grants, tables/rows, backup/restore.
- **Controller slice:** `@WebMvcTest` for `MongoController` + `PostgresController` + `ProvisionController`, prefix routes, legacy redirect.
- **Concurrency:** `ProvisioningConcurrencyTest` — same name different engines both succeed, same engine same name one fails.

### 5.4 Rollback

- **Phase 1:** `POSTGRES_ENABLED=false` disables PG — app runs Mongo-only. Remove `postgres`/`adminer` services, no data loss (metadata in Mongo).
- **Phase 2-4:** Feature flags per phase — `app.postgres.enabled`, `app.postgres.encryption-enabled`, `app.postgres.backup-enabled`.

### 5.5 Research Sources (2026-08-27)

- Spring Boot 4.1.1 Reference + 4.1 Release Notes
- PostgreSQL 18.6 Docs — `sql-createdatabase`, `sql-createrole`, `sql-grant`, `ddl-priv`, `sql-alterdefaultprivileges`, `auth-pg-hba-conf`, `libpq-connect`, `release-18`
- Docker Hub — `postgres` (18.6-alpine, PGDATA change), `adminer` (6.0.1-standalone)
- Testcontainers — `modules/databases/postgres`
- Adminer — `adminer.org` (6.0.1)
- OWASP — Database Security, Cryptographic Storage

---

Say `go Phase 1` and I'll implement in order: `pom`/`compose`/`application.yml` → `DatabaseEngineType` + `ManagedDatabase` composite id → `PostgresDatabaseRepository` (least-privilege) → `DatabaseEngine` abstraction → `ProvisioningService` dual-engine → prefix routes + provision-first nav with back button.
