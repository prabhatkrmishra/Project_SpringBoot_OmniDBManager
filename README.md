# OmniDB Manager

**Enterprise triple-engine database control plane — MongoDB + PostgreSQL 18 + MySQL 8.4.**

A self-hosted, Atlas-style provisioning service. Sign in as admin, provision isolated databases with dedicated per-database users on any engine, hand the connection string to your apps, and later rotate passwords, manage tables/rows, back up/restore, or delete — all from a single Spring MVC web UI.

> If you like this project, give it a [⭐ on GitHub](https://github.com/prabhatkrmishra/Project_SpringBoot_OmniDBManager) — it keeps me going!

## Why OmniDB Manager?

Most teams run MongoDB *and* PostgreSQL *and* MySQL. OmniDB Manager unifies them under one control plane with strict isolation:

- **Isolated engines** — every database is `engine:dbName` (`MONGO:myapp` vs `POSTGRES:myapp` vs `MYSQL:myapp`). Same name can exist in all three engines without collision. Compound unique `(engineType, dbName)` + `DatabaseLockRegistry` key `engine:dbName`.
- **Prefix routes** — `/mongo/*`, `/postgres/*`, and `/mysql/*` with a mandatory engine chooser. No ambiguous default engine.
- **Least-privilege by default** — Mongo `readWrite` scoped to one DB; Postgres `CONNECT` + `USAGE,CREATE ON SCHEMA public` + `ALTER DEFAULT PRIVILEGES` with `REVOKE CREATE ON SCHEMA public FROM PUBLIC`; MySQL `SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,... ON db.*` via `GRANT` with `caching_sha2_password`.
- **Not a proxy** — the app is the control plane only. Apps connect directly to MongoDB/PostgreSQL/MySQL via the issued connection string.

## Features

- **Triple-engine provisioning** — `POST /mongo/databases` + `POST /postgres/databases` + `POST /mysql/databases` via `DatabaseEngine` abstraction (`MongoDatabaseEngine` / `PostgresDatabaseEngine` / `MysqlDatabaseEngine`).
- **Engine-aware validation** — `DatabaseNameValidator`: Mongo `[A-Za-z0-9_-]+` max 64; Postgres `^[a-z_][a-z0-9_]*$` max 63; MySQL `^[a-z_][a-z0-9_]*$` max 64, lowercased, system DBs blocked (`admin/local/config/mongodb_admin` vs `postgres/template0/template1` vs `information_schema/mysql/performance_schema/sys`).
- **Connection strings on demand** — shown after create/reset and re-derived from stored metadata. Mongo `mongodb://user:pass@host/db?authSource=db`; Postgres `postgresql://user:pass@host:5432/db?sslmode=require&application_name=omnidb` (or `verify-full` with CA); MySQL `mysql://user:pass@host/db?sslMode=REQUIRED` (or `VERIFY_IDENTITY` with CA).
- **Password rotation & deletion** — reset instantly invalidates old password; delete drops DB + role/user + metadata (per-engine).
- **Native table/row CRUD** — Postgres via `ctid::text AS __pg_ctid` + `DELETE ... WHERE ctid = ?::tid`; MySQL via PK detection (`information_schema.KEY_COLUMN_USAGE`) + `DELETE ... WHERE pk = ?` (composite PKs rejected, delete via phpMyAdmin). Add columns on-the-fly (`ALTER TABLE ... ADD COLUMN TEXT`). No Adminer/phpMyAdmin required for CRUD.
- **Explorer** — browse databases → collections/tables → paginated documents/rows (50/page) + JSON export (per-engine).
- **Statistics & monitor** — `dbStats`/`collStats` (Mongo) + `pg_total_relation_size`/`pg_stat_user_tables` (Postgres) + `information_schema.TABLES` (MySQL), live SSE monitor with per-engine filter `?engine=mongo|postgres|mysql`.
- **Backup & restore** — Mongo gzip'd canonical Extended JSON (`formatVersion:1`); Postgres & MySQL JDBC gzip'd JSON dumps (`formatVersion:1`, `tables`/`rows`/`columns`). Streaming download, replace-semantics restore with pre-validation.
- **Encryption at rest** — `AES-256-GCM` (`ENC:v1:`) for stored per-database passwords (all engines). Key from `APP_ENCRYPTION_KEY` (base64 32B or 64 hex); plaintext fallback when blank (dev only).
- **Hardening** — per-engine rate limit (`IP:engine`, 5/min, `trustXFF=false`), TLS (`sslmode=require` / `verify-full` + `application_name` for PG; `sslMode=REQUIRED` / `VERIFY_IDENTITY` for MySQL), `scram-sha-256` / `caching_sha2_password`, `PGDATA=/var/lib/postgresql/18/docker`, audit trail (`PROVISION/RESET_PASSWORD/DELETE/TABLE_CREATED/DROPPED/TRUNCATED/ROW_INSERTED/ROW_DELETED/BACKUP_CREATED/RESTORED/IMPORT`), Micrometer `provisioned.databases{engine}` gauge, `postgres` + `mysql` HealthIndicators.
- **Brute-force protection** — login rate limit per IP+username (5/15m, 429 + `Retry-After`).
- **Bundled UIs (optional)** — mongo-express at `/mongo-express`, Adminer at `/adminer` (Postgres), phpMyAdmin at `/phpmyadmin` (MySQL), all loopback-bound and behind app auth.

## Stack

- **Java 25**, **Spring Boot 4.1.0** (`spring-boot-starter-webmvc`, `data-mongodb`, `jdbc`, `security`, `validation`, `actuator`, `micrometer`)
- **MongoDB 8** + **PostgreSQL 18.6-alpine** + **MySQL 8.4** + **Adminer 6.0.1-standalone** + **phpMyAdmin 5.2**
- **PostgreSQL driver 42.7.5** + **MySQL Connector/J 9.4**, **HikariCP** (via `spring-boot-starter-jdbc`)
- **Thymeleaf** + **Bootstrap 5.3.8** + **Bootstrap Icons 1.13.1**
- **Docker Compose** for local stack; **Testcontainers** for integration tests

## Prerequisites

- JDK 25
- Docker Desktop (or any Docker daemon) with Docker Compose
- No global Maven needed — ships `mvnw` wrapper

## Quick start (local)

1. Create env file (once):

   ```bash
   cp .env.example .env
   ```

   Edit `.env`:
   - `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` — web UI login
   - `MONGODB_ROOT_USERNAME` / `MONGODB_ROOT_PASSWORD` — Mongo root for provisioning
   - `POSTGRES_ROOT_USER` / `POSTGRES_ROOT_PASSWORD` — Postgres superuser for DDL
   - `MYSQL_ROOT_PASSWORD` — MySQL root for DDL
   - `MONGO_EXPRESS_*` — mongo-express basic auth
   - `APP_ENCRYPTION_KEY` — `openssl rand -base64 32` (or `openssl rand -hex 32`)

2. Start backing services:

   ```bash
   docker compose up -d              # all engines (mongo + postgres + mysql)
   # or per-engine:
   # docker compose -f compose.mongo.yaml up -d
   # docker compose -f compose.postgres.yaml up -d
   # docker compose -f compose.mysql.yaml up -d
   ```

   - App: http://localhost:9811
   - mongo-express: no direct URL — open **Mongo Express** from sidebar (`/mongo-express`)
   - Adminer: http://localhost:9815 (or via app proxy, loopback-bound)
   - phpMyAdmin: http://localhost:9817 (or via app proxy `/phpmyadmin`, loopback-bound)

3. Run the app:

   ```bash
   ./mvnw spring-boot:run
   ```

4. Sign in at http://localhost:9811/login, pick an engine (**MongoDB**, **PostgreSQL**, or **MySQL**), then **Provision a database**. Copy the connection string — it stays viewable on the database detail page.

### Enable PostgreSQL

Postgres is opt-in:

```bash
# .env
POSTGRES_ENABLED=true
POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres
# optional public host for issued strings
POSTGRES_PUBLIC_HOST=postgres.example.com
POSTGRES_PUBLIC_TLS=false
POSTGRES_PUBLIC_SSLMODE=require
```

Restart the app. Dashboard now shows Postgres tables; provision via **PostgreSQL → New Database**.

### Enable MySQL

MySQL is opt-in (8.4 LTS):

```bash
# .env
MYSQL_ENABLED=true
MYSQL_URI=jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
# optional public host for issued strings
MYSQL_PUBLIC_HOST=mysql.example.com
MYSQL_PUBLIC_TLS=false
MYSQL_PUBLIC_SSLMODE=REQUIRED
```

Restart the app. Dashboard now shows MySQL tables; provision via **MySQL → New Database**. MySQL uses `caching_sha2_password` and `utf8mb4`/`utf8mb4_0900_ai_ci` by default.

### Run from the compiled jar (no repo needed)

The jar is the web layer only — it connects to MongoDB/PostgreSQL/MySQL, it does not start them.

1. Build once (from repo):

   ```bash
   ./mvnw clean package
   ```

2. Copy `target/omnidb-manager-*.jar` (or `mongodbserver-*.jar` for older tags), `.env`, and `compose*.yaml` to the server.

3. Start backing services:

   ```bash
   docker compose up -d              # all engines (via compose.yaml include)
   # or per-engine:
   # docker compose -f compose.mongo.yaml up -d
   # docker compose -f compose.postgres.yaml up -d
   ```

4. Run:

   ```bash
   java -jar omnidb-manager-*.jar
   ```

The jar connects to MongoDB at `spring.mongodb.uri` (default `mongodb://<root>:<pass>@127.0.0.1:9812/?authSource=admin`) and Postgres at `app.postgres.uri` (default `jdbc:postgresql://127.0.0.1:9813/postgres`). Without those services every provision fails.

### Memory footprint

`deploy/deploy.sh` starts the jar with tuned JVM flags, measured at **~205 MB RSS** on JDK 25 (vs ~360 MB with plain `-Xms256m -Xmx512m`):

```bash
java -Xms64m -Xmx256m -XX:+UseSerialGC -XX:+UseCompactObjectHeaders \
     -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=96m -Xss512k \
     -jar omnidb-manager-*.jar
```

- `-XX:+UseSerialGC` — lowest overhead for small heaps
- `-XX:+UseCompactObjectHeaders` (JDK 25+) — trims every object
- Caps bound metaspace, code cache and thread stacks

Override via `JAVA_OPTS` before `deploy.sh`. For **1 GB-RAM servers**:

- `compose.mongo.yaml` pins guardrails: WiredTiger cache 256 MB, `--maxConns 500`, 64k nofile ulimit, 650 MB container cap. Raise together on bigger boxes.
- Remove `mongo-express`/`adminer` if unused — app works without them (`/mongo-express` → 502).
- Large restores need heap headroom: keep uploads well under 256 MB with `-Xmx256m`, or raise `-Xmx`.

### Verifying the deployment

```bash
bash deploy/verify-memory-config.sh          # config vs expected profile (small|medium)
bash deploy/load-test-mongo.sh 15 4          # data-plane ops/sec (throwaway db, auto-dropped)
bash deploy/load-test-app.sh                 # admin UI throughput + latency percentiles
```

`verify-memory-config.sh` fails loudly when reality drifts from the expected profile.

### Reverse proxy (nginx)

App binds loopback-only; put nginx in front for HTTPS. Template at `deploy/nginx.conf.example` handles:

- 256 MB `client_max_body_size` (restore uploads)
- Buffering off + long `proxy_read_timeout` for SSE `/monitor/stream`
- `X-Forwarded-For` passthrough — set `RATE_LIMIT_TRUST_XFF=true` in `.env` so rate limiters key on real IPs

Same template shows exposing **MongoDB** via nginx `stream` (TCP) with TLS termination + per-IP `limit_conn`. Then:

```bash
MONGODB_PUBLIC_HOST=your.domain.com
MONGODB_PUBLIC_TLS=true
```

For Postgres TLS (enterprise):

```bash
POSTGRES_PUBLIC_HOST=postgres.example.com
POSTGRES_PUBLIC_TLS=true
POSTGRES_PUBLIC_SSLMODE=verify-full
POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=verify-full&sslrootcert=./certs/ca.crt
```

Mount certs in `compose.postgres.yaml` (see commented `postgres` service) and set `pg_hba.conf: hostssl all all 0.0.0.0/0 scram-sha-256`. Issued strings then carry `sslmode=verify-full`.

## Using the provisioned database

### MongoDB (Node.js)

```js
const { MongoClient } = require("mongodb");
const client = new MongoClient("mongodb://myapp_user:MyStrongPass@127.0.0.1:9812/myapp?authSource=myapp");
await client.connect();
```

User has `readWrite` on `myapp` only; password rotation is instant.

### PostgreSQL (Node.js `pg` / any libpq client)

```
postgresql://myapp_user:MyStrongPass@127.0.0.1:9813/myapp?sslmode=require&application_name=omnidb
```

JDBC alternative:

```
jdbc:postgresql://127.0.0.1:9813/myapp?sslmode=require&ApplicationName=omnidb
```

With `verify-full`:

```
postgresql://myapp_user:MyStrongPass@postgres.example.com:5432/myapp?sslmode=verify-full&sslrootcert=/path/to/ca.crt&application_name=omnidb
```

### Direct connection — not a proxy

OmniDB Manager is the **control plane** only: it provisions DBs, issues per-DB credentials, and rotates/deletes them. It never sits in the data path. Connection strings point straight at MongoDB/PostgreSQL, so apps talk directly over the native wire protocol. After provisioning, the manager is needed only for admin operations.

## Atlas / external MongoDB

Set `MONGODB_URI` in `.env` to a deployment URI with root privileges:

```
MONGODB_URI=mongodb+srv://root:root@cluster0.xxxxx.mongodb.net/?authSource=admin
```

Run with `atlas` profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=atlas
# or jar:
java -jar omnidb-manager-*.jar --spring.profiles.active=atlas
```

Issued connection strings derive from the active `spring.mongodb.uri` host.

## Configuration reference

| Variable | Default | Description |
|---|---|---|
| `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` | `admin` | Web UI admin login |
| `MONGODB_ROOT_USERNAME` / `MONGODB_ROOT_PASSWORD` | `root` | Mongo root for provisioning |
| `MONGODB_URI` | `mongodb://<root>:<pass>@127.0.0.1:9812/?authSource=admin&maxPoolSize=10` | Override for Atlas/external |
| `MONGODB_PUBLIC_HOST` | *(derived)* | Host in issued Mongo strings (domain behind proxy) |
| `MONGODB_PUBLIC_TLS` | `false` | Append `&tls=true` to Mongo strings |
| `POSTGRES_ENABLED` | `false` | Enable PostgreSQL engine |
| `POSTGRES_ROOT_USER` / `POSTGRES_ROOT_PASSWORD` | `root` | Postgres superuser for DDL |
| `POSTGRES_URI` | `jdbc:postgresql://127.0.0.1:9813/postgres` | JDBC URL for admin DataSource |
| `POSTGRES_PUBLIC_HOST` | *(derived)* | Host in issued Postgres strings |
| `POSTGRES_PUBLIC_TLS` / `POSTGRES_PUBLIC_SSLMODE` | `false` / `require` | TLS for Postgres strings (`require` or `verify-full`) |
| `MYSQL_ENABLED` | `false` | Enable MySQL engine |
| `MYSQL_ROOT_PASSWORD` | `root` | MySQL root for DDL |
| `MYSQL_URI` | `jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` | JDBC URL for admin DataSource |
| `MYSQL_PUBLIC_HOST` | *(derived)* | Host in issued MySQL strings |
| `MYSQL_PUBLIC_TLS` / `MYSQL_PUBLIC_SSLMODE` | `false` / `REQUIRED` | TLS for MySQL strings (`REQUIRED` or `VERIFY_IDENTITY`) |
| `PHPMYADMIN_BASE_URL` | `http://127.0.0.1:9817` | phpMyAdmin base URL |
| `ADMINER_BASE_URL` | `http://127.0.0.1:9815` | Adminer base URL |
| `MONGO_EXPRESS_BASE_URL` | `http://127.0.0.1:9814/mongo-express` | mongo-express base URL |
| `APP_ENCRYPTION_KEY` | *(empty = plaintext)* | Base64 32B or 64 hex for AES-256-GCM (`ENC:v1:`) |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind address (`0.0.0.0` to expose) |
| `RATE_LIMIT_TRUST_XFF` | `false` | Honor `X-Forwarded-For` for rate limiters (behind trusted proxy) |
| `app.login-rate-limit.*` | `5 / 15m` | Login brute-force window |
| `app.provision-rate-limit.*` | `5 / 1m` | Per-engine provision/reset/delete window (`IP:engine`) |

`application.yml` also sets `management.endpoints.web.exposure=health,info,metrics` and `show-components:always`.

## Architecture

```
Controller  →  Service  →  Repository (Mongo Java driver / JdbcTemplate)
     │            │
     └──── Thymeleaf views (server-rendered, th:text only)
```

- `ProvisioningService` — lifecycle: provision / reset / delete / list (per-engine, `DatabaseLockRegistry` `engine:dbName`, `Clock` for audit, `EncryptionService` `ENC:v1:` for all engines).
- `DatabaseEngine` — `MongoDatabaseEngine` (wraps `MongoDatabaseRepository`) + `PostgresDatabaseEngine` (wraps `PostgresDatabaseRepository` via `JdbcTemplate`, no `@Transactional` — `CREATE/DROP DATABASE` cannot run in a transaction) + `MysqlDatabaseEngine` (wraps `MysqlDatabaseRepository`, backtick quoting, `caching_sha2_password`, `utf8mb4`).
- `PostgresDatabaseRepository` — `CREATE DATABASE "db" OWNER "user" TEMPLATE template0`, `CREATE ROLE ... WITH LOGIN PASSWORD`, `pg_terminate_backend`, `REVOKE CREATE ON SCHEMA public FROM PUBLIC`, `quoteIdentifier`, `executeInDatabase`, `listTables`/`listRowsWithCtid` (`ctid::text AS __pg_ctid`).
- `MysqlDatabaseRepository` — ``CREATE DATABASE `db` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci``, ``CREATE USER 'u'@'%' IDENTIFIED BY ?`` + `GRANT ... ON db.*`, `information_schema` sizes/tables/columns, `quoteIdentifier` backticks, `getPrimaryKeyColumn` single-PK guard.
- `ExplorationService` / `PostgresExplorationService` / `MysqlExplorationService` — read-only browsing, bounded pagination (50/page), JSON export, PK-aware delete.
- `PostgresBackupService` / `MysqlBackupService` / `BackupService` — gzip'd JSON dumps (`formatVersion:1`), streaming, replace-semantics restore.
- `ManagedDatabaseRepository` — Spring Data metadata in `mongodb_admin` (stores encrypted per-DB password, `id=engine:dbName`, `countByEngineType`).
- `SecurityConfig` — form login, CSRF on, `hasRole(ADMIN)` for `/postgres/databases/**`, `/mysql/databases/**`, `/phpmyadmin/**` and writes.
- `ProvisionRateLimitFilter` (`IP:engine` `MONGO/POSTGRES/MYSQL`, order `-9`) + `LoginRateLimitFilter` — in-process fixed-window.
- `PostgresHealthIndicator` + `MysqlHealthIndicator` + `ProvisionedDatabaseMetrics` (`provisioned.databases{engine}` `MONGO/POSTGRES/MYSQL`).
- `EncryptionService` / `EncryptionProperties` — AES-256-GCM `ENC:v1:` (all engines).
- `MongoExpressProxyFilter` / `AdminerProxyFilter` / `PhpMyAdminProxyFilter` — reverse-proxy bundled UIs behind app auth.

Naming is validated per-engine; system databases are protected.

## PostgreSQL specifics

- **DDL** — `CREATE/DROP DATABASE` runs outside transactions (auto-commit `JdbcTemplate`). `CREATE DATABASE "db" OWNER "user" TEMPLATE template0 ENCODING 'UTF8'`; `CREATE ROLE "user" WITH LOGIN PASSWORD '...'` (`scram-sha-256`); `GRANT CONNECT` + schema grants.
- **Table/row CRUD** — `CREATE TABLE ... (col TEXT)`, `DROP TABLE IF EXISTS ... CASCADE`, `TRUNCATE ... CASCADE`, `INSERT` dynamic, `SELECT *, ctid::text AS __pg_ctid LIMIT ? OFFSET ?`, `DELETE ... WHERE ctid = ?::tid`. Columns lowercased, `distinct()`, reserved names blocked (`__pg_ctid/__ctid/ctid/__new_col/__new_val/_csrf`).
- **Connection strings** — built from `app.postgres.public-host` or parsed `spring.datasource` host, `uriEncode` for user/pass, `?sslmode=require&application_name=omnidb` (or `verify-full`).

## MySQL specifics

- **DDL** — auto-commit `JdbcTemplate` (MySQL DDL implicit commit). ``CREATE DATABASE `db` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci``; ``CREATE USER 'user'@'%' IDENTIFIED BY '...'`` (`caching_sha2_password`); ``GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES,CREATE VIEW,SHOW VIEW,TRIGGER,CREATE TEMPORARY TABLES,LOCK TABLES,EXECUTE ON `db`.* TO 'user'@'%'``.
- **Table/row CRUD** — ``CREATE TABLE `db`.`table` (col TEXT)``, ``DROP TABLE IF EXISTS `db`.`table```, ``TRUNCATE TABLE `db`.`table```, `INSERT` dynamic, ``SELECT * FROM `db`.`table` LIMIT ? OFFSET ?``, PK detection via `information_schema.KEY_COLUMN_USAGE` (`constraint_name='PRIMARY'`), single-PK guard (composite PKs → delete via phpMyAdmin), `DELETE ... WHERE pk = ?`. Columns lowercased, `distinct()`, reserved names blocked (`__mysql_pk/__mysql_pk_col/__new_col/__new_val/_csrf`).
- **Connection strings** — built from `app.mysql.public-host` or parsed `app.mysql.uri` host, `uriEncode` for user/pass, `mysql://user:pass@host/db?sslMode=REQUIRED` (or `VERIFY_IDENTITY` with CA). JDBC: `jdbc:mysql://host/db?sslMode=REQUIRED&serverTimezone=UTC`.
- **Sizes & monitor** — `SUM(data_length+index_length) FROM information_schema.TABLES`, `information_schema.PROCESSLIST` + `performance_schema.global_status` (`Com_commit`/`Com_rollback`, `Uptime` as `VARCHAR` → `Long.parseLong`).

## Releases & deployment

**Releasing** (`.github/workflows/release.yml`): tag and push — GitHub builds the jar, versions from tag, generates changelog, attaches jar to Release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Or trigger **Release** workflow manually with a tag input.

**Auto-deploy** (`deploy/deploy.sh`): on the VPS, run once:

```bash
bash deploy/setup-cron.sh
```

Cron checks latest Release every 5 minutes, downloads new jar, stops old tmux session (`omnidb`) and starts new one. Logs: `~/omnidb/deploy.log`.

CI: `.github/workflows/maven.yml` — `mvn -B clean package -DargLine=-Xmx1024m` on JDK 25 (Temurin).

## Tests

```bash
./mvnw test
# or full package (what CI runs):
./mvnw -B clean package -DargLine=-Xmx1024m
```

- Unit tests for validators, password generator, services, rate limiters, encryption, backup/restore.
- `@WebMvcTest` slices for controllers (auth, CSRF, validation, error handling).
- Testcontainers-backed tests (real MongoDB/PostgreSQL with auth) for driver repos and full provision/reset/delete lifecycle, including concurrency and rate-limit bursts. Skipped automatically when Docker is unavailable (`Tests run:266 Failures:0 Errors:0 Skipped:26` without Docker).

## Project layout

```
compose.yaml                      # orchestrator (include: mongo + postgres + mysql)
compose.mongo.yaml                # MongoDB 8 + mongo-express (standalone: -f compose.mongo.yaml)
compose.postgres.yaml             # PostgreSQL 18.6 + Adminer (standalone: -f compose.postgres.yaml)
compose.mysql.yaml                # MySQL 8.4 + phpMyAdmin (standalone: -f compose.mysql.yaml)
.env / .env.example               # credentials (gitignored)
src/main/java/com/pkmprojects/mongodbserver
  MongodbserverApplication.java   # @SpringBootApplication (excludes DataSourceAutoConfiguration when PG/MySQL disabled)
  config/                         # Security, rate limiting, PostgresConfig/MysqlConfig, EncryptionProperties, HealthIndicators, metrics, proxy filters
  controller/                     # Login, Dashboard, Database, Collection, Postgres, Mysql, Activity, Backup, Monitor
  dto/                            # Form + view objects (CreateDatabaseForm, DatabaseInfo, TableInfo, TableRowPage, MysqlDatabaseStats, ...)
  error/                          # Domain exceptions + global handler
  model/                          # AuditEvent, ManagedDatabase (id=engine:dbName, encrypted password), DatabaseEngineType
  repository/                     # MongoDatabaseRepository, PostgresDatabaseRepository, MysqlDatabaseRepository, ManagedDatabaseRepository, AuditLogRepository
  security/                       # Password generator
  service/                        # Provisioning, Exploration, PostgresExploration, MysqlExploration, Backup, Statistics, Monitor, Encryption, DatabaseNameValidator
  util/                           # Json helpers
src/main/resources
  application.yml                 # defaults (spring.mongodb.*, app.postgres.*, app.mysql.*, rate-limit, management)
  static/css/site.css             # UI styling
  static/js/app.js                # copy-to-clipboard, confirm, toggle-password helpers
  templates/                      # Thymeleaf views (login, index, database, table-rows, collections, activity, ...)
    fragments/
deploy/                           # deploy.sh, setup-cron.sh, verify-memory-config.sh, load-test-*.sh, nginx.conf.example
```

## Third-party libraries & credits

| Library | Used for | License |
|---|---|---|
| [Spring Boot](https://spring.io/projects/spring-boot) 4.1 / Spring Framework 7 | Web, security, data, validation, JDBC, actuator | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [Spring Security](https://spring.io/projects/spring-security) | Form login, CSRF, method security, filter ordering | Apache-2.0 |
| [Spring Data MongoDB](https://spring.io/projects/spring-data-mongodb) | Repository metadata + driver gateway | Apache-2.0 |
| [MongoDB Java Driver](https://www.mongodb.com/docs/drivers/java/) | Direct database/user administration | Apache-2.0 |
| [PostgreSQL JDBC](https://jdbc.postgresql.org/) 42.7.5 | PostgreSQL administration via JDBC | BSD-2-Clause |
| [Thymeleaf](https://www.thymeleaf.org/) + `thymeleaf-extras-springsecurity6` | Server-rendered views | Apache-2.0 |
| [springboot4-dotenv](https://github.com/paulschwarz/spring-dotenv) | `.env` loading | MIT |
| [Bootstrap](https://getbootstrap.com/) 5.3.8 (WebJar) | UI styling | MIT |
| [Bootstrap Icons](https://icons.getbootstrap.com/) 1.13.1 (WebJar) | UI icons | MIT |
| [Testcontainers](https://testcontainers.com/) | Integration tests (MongoDB + PostgreSQL) | MIT |
| [MongoDB](https://www.mongodb.com/) / [mongo-express](https://github.com/mongo-express/mongo-express) / [PostgreSQL](https://www.postgresql.org/) / [Adminer](https://www.adminer.org/) (Docker images) | Local dev stack | SSPL / MIT / PostgreSQL / Apache-2.0 |

## License

Apache License 2.0. See [LICENSE](LICENSE).
