# MySQL Integration Plan — Triple-Engine (MongoDB + PostgreSQL 18 + MySQL 8.4) + Explorer + Provision-First Nav

> Planning only — no code changed. Verified against `pom.xml` (Boot 4.1.0 / Java 25), `compose.mongo.yaml`, `compose.postgres.yaml`, `compose.yaml` (include orchestrator), `application.yml`, `DatabaseEngine`, `DatabaseEngineType`, `ManagedDatabase`, `ProvisioningService`, `DatabaseNameValidator`, `PostgresDatabaseRepository`, `PostgresDatabaseEngine`, `PostgresConfig`, `PostgresController`.
> Research pass: 2026-08-28 — MySQL 8.4 LTS docs (CREATE DATABASE/USER, GRANT, identifiers, auth), MySQL Connector/J 9.4/8.4, Docker Hub mysql:8.4 (amd64+arm64), Testcontainers MySQL module, Spring Boot 4.1.1 SQL docs, phpMyAdmin 5.2 / Adminer 6.0.1, OWASP DB/Crypto.

---

## 0. Research Summary (what changed the plan)

| Source | Finding | Impact on plan |
|---|---|---|
| **MySQL 8.4 LTS — CREATE DATABASE** (`dev.mysql.com/doc/refman/8.4/en/create-database.html`) | `CREATE DATABASE [IF NOT EXISTS] db_name [CHARACTER SET charset] [COLLATE collation]` — must be unique per instance. Default charset `utf8mb4` + collation `utf8mb4_0900_ai_ci` in 8.4. No `OWNER` clause (unlike PG). Requires `CREATE` privilege. | `MysqlDatabaseRepository.createDatabase(db)` → ``CREATE DATABASE `db` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`` with backtick quoting. No transaction restriction (unlike PG `CREATE DATABASE` cannot be in tx — MySQL allows it). |
| **MySQL 8.4 — CREATE USER / ALTER USER** | `CREATE USER [IF NOT EXISTS] 'user'@'host' IDENTIFIED BY 'pass'` — user is `user@host` pair. Host `'%'` = any host. Default auth `caching_sha2_password` (since 8.0). `ALTER USER 'user'@'%' IDENTIFIED BY 'newpass'` for rotation. | Use `'user'@'%'` everywhere. `createUser(db,user,pass)` → `CREATE USER 'user'@'%' IDENTIFIED BY ?` (param). `updateUserPassword` → `ALTER USER 'user'@'%' IDENTIFIED BY ?`. Quote user with single quotes, escape `'`. |
| **MySQL 8.4 — GRANT / Privileges** | `GRANT ALL PRIVILEGES ON db.* TO 'user'@'%'` — database-level. `GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP ON db.*` for least-privilege. `REVOKE ALL ON db.* FROM 'user'@'%'`. No schema layer (unlike PG `GRANT ON SCHEMA`). `FLUSH PRIVILEGES` not needed in 8.0+ (GRANT updates in-memory). | Enterprise least-privilege: `GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES,CREATE VIEW,SHOW VIEW,TRIGGER ON `db`.* TO 'user'@'%'` — not `ALL PRIVILEGES` (which includes `GRANT OPTION` if specified). Or `ALL PRIVILEGES` for simplicity with `WITH GRANT OPTION` omitted. Document both. |
| **MySQL 8.4 — DROP DATABASE / DROP USER** | `DROP DATABASE [IF EXISTS] db` — removes db even with active connections (connections get error, no `pg_terminate_backend` needed). `DROP USER [IF EXISTS] 'user'@'%'`. | `dropDatabase(db)` → ``DROP DATABASE IF EXISTS `db```. `dropUser(db,user)` → `REVOKE ALL ON `db`.* FROM 'user'@'%'` (best-effort) then `DROP USER IF EXISTS 'user'@'%'`. No terminate step. |
| **MySQL 8.4 — Identifiers** | Database/table names max 64 chars (vs PG 63). Allowed chars: alphanumeric + `_` + `$` (but restrict). Case sensitivity depends on `lower_case_table_names` (0=case-sensitive on Linux, 1=case-insensitive). Default on Linux 0. System DBs: `information_schema`, `mysql`, `performance_schema`, `sys`. | Validator: `^[a-z_][a-z0-9_]*$` max 64, lowercase, must start with letter/underscore — same as PG but max 64. Block system DBs. Quote with backticks `` `name` `` and escape `` ` `` → `` `` ``. |
| **MySQL 8.4 — information_schema** | `SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN (...)` for list. `SELECT table_name FROM information_schema.TABLES WHERE table_schema=?` for tables. `SELECT table_rows, data_length, index_length FROM information_schema.TABLES` for sizes. `SELECT COUNT(*) FROM `table`` for row count. | `listDatabaseNames()` → `SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('information_schema','mysql','performance_schema','sys') ORDER BY SCHEMA_NAME`. `listTables(db)` → `SELECT table_name FROM information_schema.TABLES WHERE table_schema=?`. |
| **MySQL Connector/J 9.4 / 8.4** (`mvnrepository.com/artifact/com.mysql/mysql-connector-j`) | Latest 9.4.0 (2025-07), 8.4.0 LTS (2024-04). Driver class `com.mysql.cj.jdbc.Driver`. URL `jdbc:mysql://host:port/db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`. Supports `sslMode=REQUIRED|VERIFY_CA|VERIFY_IDENTITY`. Boot 4.1.1 BOM manages version (check `dependency-versions`). | Add `com.mysql:mysql-connector-j` runtime, let Boot BOM manage version or pin `9.4.0`. URL `jdbc:mysql://127.0.0.1:9816/db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`. For TLS: `sslMode=REQUIRED` or `VERIFY_IDENTITY`. |
| **Docker Hub — mysql** | Tags: `8.4`, `8.4.11`, `8`, `lts`, `9.7`, `innovation`. `8.4` is LTS (supported until 2032). `amd64` + `arm64` (like postgres). Env `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`. Volume `/var/lib/mysql`. Healthcheck `mysqladmin ping -h 127.0.0.1 -u root -p$MYSQL_ROOT_PASSWORD`. | Pin `mysql:8.4` (~260 MB, larger than postgres:18.6-alpine ~80 MB). `container_name: omnidb-mysql`, port `127.0.0.1:9816:3306`, `MYSQL_ROOT_PASSWORD`, volume `mysql-data:/var/lib/mysql`, healthcheck `mysqladmin ping`. |
| **Docker Hub — phpMyAdmin / Adminer** | `phpmyadmin:5.2` (~150 MB, PHP+Apache), `adminer:6.0.1-standalone` (41.6 MB, supports MySQL+PG). Adminer already used for PG. phpMyAdmin is MySQL-specific with more features. | For MySQL explorer: **Adminer reuse** (single container can handle both PG and MySQL via dropdown) OR **phpMyAdmin** for MySQL-specific UI. Recommend **phpMyAdmin:5.2** for MySQL (familiar) + keep Adminer for PG, OR second Adminer `omnidb-mysql-adminer` on different port. Document both; default to `phpmyadmin:5.2` on `127.0.0.1:9817:80` with `PMA_HOST=mysql`. |
| **Testcontainers — MySQL Module** (`java.testcontainers.org/modules/databases/mysql/`) | `MySQLContainer("mysql:8.4")`, JDBC URL `jdbc:tc:mysql:8.4:///db`. Supports `withDatabaseName`, `withUsername`, `withPassword`. | Integration tests: `new MySQLContainer("mysql:8.4")` with `@ServiceConnection` or `@DynamicPropertySource`. Same pattern as `PostgreSQLContainer("postgres:18.6-alpine")`. |
| **Spring Boot 4.1.1 — SQL Databases** (`docs.spring.io/spring-boot/reference/data/sql.html`) | `spring.datasource.*` auto-config with HikariCP default. `spring-boot-starter-jdbc` provides `JdbcTemplate`. Multiple DataSources need `@Primary` or `@Qualifier` + `@ConditionalOnProperty`. `DataSourceBuilder` supports Hikari, Tomcat, DBCP2. | MySQL admin DataSource: separate `mysqlDataSource` bean (like `postgresDataSource`), `JdbcTemplate` `mysqlJdbcTemplate`, conditional on `app.mysql.enabled`. Use `DriverManagerDataSource` for DDL (like PG) or Hikari with `maximumPoolSize=5`. No `@Transactional` needed (MySQL DDL auto-commits, unlike PG restriction). |
| **MySQL 8.4 — Row identity** | No `ctid` like PG. Row deletion needs primary key or unique key. `information_schema.COLUMNS` + `SHOW KEYS` to find PK. If no PK, need to use `LIMIT 1` with all column values (fragile). | For table/row CRUD: `listRowsWithId` needs PK detection. If table has PK, `DELETE FROM `table` WHERE pk=?`. If no PK, fallback to `DELETE ... WHERE col1=? AND col2=? LIMIT 1` or require PK. Document limitation. Simpler: require PK for delete, or use `ROW_NUMBER()` not available. For MVP, use `DELETE ... WHERE id=?` if `id` column exists, else all-columns match. |
| **OWASP DB Security** | Least privilege, strong passwords, TLS, separate DB per app, audit, no `root` for apps. MySQL `caching_sha2_password` is current (not `mysql_native_password` deprecated). | Apply: per-DB user with `GRANT` on `db.*` only, `caching_sha2_password` (default), TLS `sslMode=REQUIRED` when `MYSQL_PUBLIC_TLS=true`, audit per engine, `storedPassword` encrypted via `EncryptionService` (reuse). |

---

## 1. Stack Pin

### Java 25 — no `pom.xml` version change

- Already `java.version=25` + `spring-boot-starter-parent:4.1.0` (Java 25-native).
- Keep `deploy.sh` flags: `-XX:+UseCompactObjectHeaders` (Java 25+), `-XX:+UseSerialGC`, `-Xms64m -Xmx256m`.
- New code may use `Executors.newVirtualThreadPerTaskExecutor()` for MySQL stats fan-out (like PG).

### MySQL 8.4 LTS — GA 2024-04-30, latest 8.4.11 (2026)

- Pin `mysql:8.4` (LTS, Oracle Linux 9 slim, ~260 MB). `8.4.11-oraclelinux9` for explicit patch.
- Driver `com.mysql:mysql-connector-j:9.4.0` (latest) or `8.4.0` LTS — Boot 4.1.1 BOM manages version; verify `mvn dependency:tree` shows correct.
- DDL: `CREATE DATABASE` + `CREATE USER` + `GRANT` — all auto-commit, no transaction restriction (unlike PG).
- **Docker volume:** `/var/lib/mysql` (not PG's `/var/lib/postgresql`), no `PGDATA` equivalent.

### Explorer — phpMyAdmin 5.2 vs Adminer 6.0.1

| Option | Image | Size | Auth | Proxy complexity | Verdict |
|---|---|---|---|---|---|
| **phpMyAdmin** | `phpmyadmin:5.2` | ~150 MB | Own login (or `PMA_USER`/`PMA_PASSWORD`) | Medium — PHP session, `PMA_HOST` | **Recommended for MySQL** — familiar, MySQL-specific features (import/export, designer) |
| **Adminer** | `adminer:6.0.1-standalone` | 41.6 MB | None (relies on app login) | Trivial — single PHP, no session | **Light alternative** — reuse same image, second container `omnidb-mysql-adminer` on different port, or single Adminer handles both PG+MySQL via dropdown |
| **MySQL Shell** | `mysql:8.4` client | — | CLI | None | For `docker exec` only |

**Recommendation:** `phpmyadmin:5.2` on `127.0.0.1:9817:80` with `PMA_HOST=mysql`, `PMA_PORT=3306`, `UPLOAD_LIMIT=256M`, loopback-bound, proxied behind app login via `MysqlAdminProxyFilter` (clone of `MongoExpressProxyFilter`/`AdminerProxyFilter`). Keep Adminer for PG on 9815. Alternative: second Adminer on 9817 if size matters.

---

## 2. Goal / Non-Goals

**Goal (enterprise-grade):** Provision **MongoDB**, **PostgreSQL 18**, and **MySQL 8.4** as **fully separate engines** — separate nav, routes, connection strings, health, audit, and explorer. Same DB name allowed across all three (e.g. `myapp` in MONGO, POSTGRES, and MYSQL). Each DB gets a dedicated per-DB user with **least-privilege** grants, TLS-ready connection strings, and audit trail. All three coexist; metadata stays in `mongodb_admin` with `engineType`.

**Non-goals (Phase 1):**
- Move metadata (`provisioned_databases`, `admin_activity`) to MySQL/JPA
- MySQL exploration (tables/rows), `information_schema` stats, backup/restore, `mysqldump`
- Replace Mongo/Postgres — they stay

**Enterprise-grade additions (Phase 1):**
- Least-privilege MySQL grants (not `ALL PRIVILEGES` with `GRANT OPTION`), `caching_sha2_password`, TLS via `sslMode`, `application_name` equivalent via `connectionAttributes`
- Separate engine contexts — no mixed lists, no cross-engine leakage
- Composite identity `engine:dbName` — same name allowed across all three

---

## 3. Key Design Decisions

| Decision | Choice | Why |
|---|---|---|
| **Metadata store** | Keep in Mongo (`mongodb_admin`) | Zero migration, backward compat. Add `MYSQL` to `DatabaseEngineType`. |
| **Engine abstraction** | `DatabaseEngine` interface + `MongoEngine` + `PostgresEngine` + `MysqlEngine` (new `MysqlDatabaseRepository` via `JdbcTemplate`) | Isolates driver specifics; `ProvisioningService` becomes orchestrator only. Engines never share state. |
| **DB identity** | **Composite `engine + dbName` — same name ALLOWED across all three** | User requested `myapp` can exist in all. `ManagedDatabase.id = engine + ":" + dbName`, unique index on `(engineType, dbName)`. `DatabaseLockRegistry` key = `engine:dbName`. Routes are prefixed so no ambiguity. |
| **Route scheme** | **Prefix mandatory: `/mongo/databases/{name}` + `/postgres/databases/{name}` + `/mysql/databases/{name}`** | Consistent with existing. No fallback to `/databases/{name}` (or 301 redirect for backward compat). Nav active state is unambiguous. |
| **Connection strings** | `mongodb://user:pass@host/db?authSource=db` vs `postgresql://user:pass@host:5432/db?sslmode=require&application_name=omnidb` vs `mysql://user:pass@host:3306/db?sslMode=REQUIRED` + `jdbc:mysql://host:3306/db?useSSL=true&requireSSL=true` | MySQL string built from `app.mysql.public-host` / `spring.datasource` host, with `uriEncode` reuse. Enterprise: `sslMode=REQUIRED` when `MYSQL_PUBLIC_TLS=true`, `VERIFY_IDENTITY` when CA available. |
| **Validation** | Extend `DatabaseNameValidator` with `validateMysql*` | MySQL identifiers: `^[a-z_][a-z0-9_]*$` must start with letter, lowercased, max 64 chars (MySQL limit). Same as PG but max 64 vs 63. System DBs: `information_schema`, `mysql`, `performance_schema`, `sys`. |
| **SQL safety** | Never interpolate identifiers; use `quoteIdentifier()` (backticks) + `PreparedStatement` for passwords | Prevents SQL injection. MySQL uses backticks `` `name` `` not double quotes. |
| **Admin DataSource** | Separate `mysqlAdminDataSource` (root) for DDL; `MongoClient` unchanged, `postgresDataSource` unchanged | MySQL `CREATE DATABASE` requires `CREATE` privilege (root has it). Use `app.mysql.uri` with `MYSQL_ROOT_USER`. Engines have separate pools (Hikari 5 for MySQL, 5 for PG, 10 for Mongo). |
| **Transaction handling** | **No `@Transactional` on MySQL DDL either** — MySQL DDL auto-commits (implicit commit), but not forbidden like PG. Keep consistent: no `@Transactional` on `MysqlDatabaseRepository`. | `JdbcTemplate` auto-commit. MySQL `CREATE DATABASE`/`DROP DATABASE` cause implicit commit. |
| **Explorer** | phpMyAdmin `5.2` loopback-bound, proxied behind app login | Same pattern as `mongo-express`/`adminer`. 502 when down. Enterprise can swap to Adminer via one-line compose change. |
| **Separation** | **Mongo, Postgres, MySQL fully separate** — separate nav, controllers, services, health, stats, audit `engine` field | No mixed `listDatabases()`; dashboard shows three separate cards/tables. Prevents cross-engine confusion. |
| **Privileges (enterprise)** | **Least-privilege, not `ALL PRIVILEGES`** — `SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES,CREATE VIEW,SHOW VIEW,TRIGGER,CREATE TEMPORARY TABLES,LOCK TABLES,EXECUTE` on `db.*` | OWASP least privilege. `ALL PRIVILEGES` would include `GRANT OPTION` if specified; avoid. Future tables auto-covered via `db.*` wildcard. No `REVOKE CREATE ON SCHEMA` needed (MySQL has no schema layer). |
| **Row identity** | **PK-based deletion** — detect PK via `information_schema.KEY_COLUMN_USAGE` or `SHOW KEYS`, delete by PK. If no PK, fallback to all-columns match with `LIMIT 1` or reject. | MySQL has no `ctid` like PG. Must handle PK detection. Document limitation: tables without PK require full row match. |

---

## 4. File-Level Change Plan

### 4.1 Build / Infra

**`pom.xml`** — add (Boot 4.1.1 managed versions):

```xml
<dependency>
  <groupId>com.mysql</groupId>
  <artifactId>mysql-connector-j</artifactId>
  <scope>runtime</scope>
</dependency>
<!-- test -->
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-mysql</artifactId>
  <scope>test</scope>
</dependency>
```

Keep `spring-boot-starter-jdbc` (already present for PG), `postgresql:42.7.13`, `spring-boot-starter-data-mongodb`. Boot 4.1.1 manages `mysql-connector-j:9.1.0` (verify via `mvn dependency:tree` — if older, pin `9.4.0` explicitly).

**`compose.mysql.yaml`** — new file (mirrors `compose.mongo.yaml` + `compose.postgres.yaml`):

```yaml
# MySQL engine — run standalone with:
#   docker compose -f compose.mysql.yaml up -d
# Or all engines via the orchestrator:
#   docker compose up -d
services:
  mysql:
    image: mysql:8.4
    container_name: omnidb-mysql
    restart: unless-stopped
    ports:
      - "127.0.0.1:9816:3306"
    environment:
      MYSQL_ROOT_USER: ${MYSQL_ROOT_USER:-root}
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
      # MYSQL_DATABASE not set — app creates per-DB via DDL
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-u", "root", "-p${MYSQL_ROOT_PASSWORD:-root}"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s  # MySQL init slower than PG (timezone load)
    command: ["mysqld", "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci", "--default-authentication-plugin=caching_sha2_password"]
    # TLS (enterprise) — uncomment and provide certs:
    # volumes:
    #   - ./certs/mysql-server.crt:/etc/mysql/certs/server.crt:ro
    #   - ./certs/mysql-server.key:/etc/mysql/certs/server.key:ro
    #   - ./certs/ca.crt:/etc/mysql/certs/ca.crt:ro
    # command: ["mysqld", "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
    #   "--require-secure-transport=ON", "--ssl-ca=/etc/mysql/certs/ca.crt",
    #   "--ssl-cert=/etc/mysql/certs/server.crt", "--ssl-key=/etc/mysql/certs/server.key"]

  phpmyadmin:
    image: phpmyadmin:5.2
    container_name: omnidb-phpmyadmin
    restart: unless-stopped
    ports:
      - "127.0.0.1:9817:80"
    environment:
      PMA_HOST: mysql
      PMA_PORT: 3306
      PMA_USER: ${MYSQL_ROOT_USER:-root}
      PMA_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
      UPLOAD_LIMIT: 256M
    depends_on:
      mysql:
        condition: service_healthy

volumes:
  mysql-data:
```

**Alternative (light):** Replace `phpmyadmin` with `adminer:6.0.1-standalone` on `127.0.0.1:9817:8080` with `ADMINER_DEFAULT_SERVER=mysql` — 41.6 MB vs 150 MB, single PHP, no session. Document as swap.

**`compose.yaml`** — update `include`:

```yaml
include:
  - compose.mongo.yaml
  - compose.postgres.yaml
  - compose.mysql.yaml
```

**`.env.example`** — add:

```
# Local MySQL root credentials
MYSQL_ROOT_USER=root
MYSQL_ROOT_PASSWORD=change-me-now

# MySQL connection (used when MYSQL_ENABLED=true)
# MYSQL_URI=jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC

# Public MySQL host used in the connection strings shown in the UI
# MYSQL_PUBLIC_HOST=mysql.example.com
# MYSQL_PUBLIC_TLS=false
# MYSQL_PUBLIC_SSLMODE=REQUIRED  # or VERIFY_CA, VERIFY_IDENTITY when CA available
# For VERIFY_IDENTITY with CA:
# MYSQL_PUBLIC_SSLMODE=VERIFY_IDENTITY
# MYSQL_URI=jdbc:mysql://127.0.0.1:9816/mysql?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:./certs/ca.crt

# phpMyAdmin (MySQL explorer) base URL
# PHPMYADMIN_BASE_URL=http://127.0.0.1:9817
```

**`src/main/resources/application.yml`** — add:

```yaml
app.mysql.enabled: ${MYSQL_ENABLED:false}
app.mysql.uri: ${MYSQL_URI:jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}
app.mysql.public-host: ${MYSQL_PUBLIC_HOST:}
app.mysql.public-tls: ${MYSQL_PUBLIC_TLS:false}
app.mysql.public-sslmode: ${MYSQL_PUBLIC_SSLMODE:REQUIRED}
app.phpmyadmin.base-url: ${PHPMYADMIN_BASE_URL:http://127.0.0.1:9817}
```

Note: `spring.datasource` is already used for PG — cannot reuse for MySQL. Use `app.mysql.uri` + custom `MysqlConfig` DataSource (like `PostgresConfig`). Do NOT set `spring.datasource.url` for MySQL; keep PG's `spring.datasource` as is, add `app.mysql.*` separate.

### 4.2 Domain / DTO

- **`model/DatabaseEngineType.java`** — add `MYSQL` enum value: `MONGO, POSTGRES, MYSQL`
- **`model/ManagedDatabase.java`** — no change needed (already `engineType` field, composite id `engine:dbName` handles MYSQL). Ensure `getEngineType()` default handles null → MONGO (existing).
- **`dto/DatabaseInfo.java`** — no change (already `engineType` field handles MYSQL).
- **`dto/CreateDatabaseForm.java`** — no change (already `engineType` field, validation will handle MYSQL).
- **`dto/DatabaseUser.java`** — reuse for MySQL (roles = `SELECT,INSERT,...` etc).
- **`model/AuditEvent.java`** — no change (already `engineType` field).

### 4.3 Repository Layer

**`repository/MysqlDatabaseRepository.java`** (new) — `JdbcTemplate` based, **no `@Transactional`**:

- `quoteIdentifier(String)` → `` ` `` + `name.replace("`","``")` + `` ` `` — MySQL backticks, not PG double quotes.
- `quoteUser(String)` → `'user'@'%'` with `user.replace("'","''")` for single quotes.
- `listDatabaseNames()` → `SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('information_schema','mysql','performance_schema','sys') ORDER BY SCHEMA_NAME`
- `databaseExists(db)` → `SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?`
- `getDatabaseSize(db)` → `SELECT SUM(data_length + index_length) FROM information_schema.TABLES WHERE table_schema=?` (MySQL has no `pg_database_size` equivalent; sum of tables).
- `getDatabaseSizes()` → `SELECT table_schema, SUM(data_length+index_length) AS sz FROM information_schema.TABLES GROUP BY table_schema`
- `createDatabase(db)` → ``CREATE DATABASE `db` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`` — use `quoteIdentifier`.
- `dropDatabase(db)` → ``DROP DATABASE IF EXISTS `db``` — no terminate step (unlike PG).
- `createUser(db, user, pass)` → `CREATE USER 'user'@'%' IDENTIFIED BY ?` (param `pass`) — use `PreparedStatement` for password, not string interpolation. MySQL 8.4 requires `caching_sha2_password` default, no extra clause.
- `grantPrivileges(db, user)` → ``GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES,CREATE VIEW,SHOW VIEW,TRIGGER,CREATE TEMPORARY TABLES,LOCK TABLES,EXECUTE ON `db`.* TO 'user'@'%'`` — least-privilege. Alternative: `GRANT ALL PRIVILEGES ON `db`.* TO 'user'@'%'` for simplicity (document trade-off).
- `updateUserPassword(db, user, pass)` → `ALTER USER 'user'@'%' IDENTIFIED BY ?`
- `dropUser(db, user)` → ``REVOKE ALL PRIVILEGES ON `db`.* FROM 'user'@'%'`` (best-effort, may fail if DB already dropped) then `DROP USER IF EXISTS 'user'@'%'`
- `getUsers(db)` → `SELECT user FROM mysql.db WHERE db=? AND host='%'` OR `SELECT user FROM mysql.user WHERE user NOT IN ('root','mysql.sys','mysql.session','mysql.infoschema')` filtered by `has_priv` — simpler: `SELECT DISTINCT user FROM mysql.db WHERE db=?` OR query `information_schema`? Actually `mysql.db` table stores db-level grants. Use `SELECT user FROM mysql.db WHERE db=?` for users with grants on db.
- `listTables(db)` → `SELECT table_name FROM information_schema.TABLES WHERE table_schema=? AND table_type='BASE TABLE' ORDER BY table_name` — need to `USE db` or specify `table_schema`.
- `tableExists(db, table)` → `SELECT COUNT(*) FROM information_schema.TABLES WHERE table_schema=? AND table_name=?`
- `countRows(db, table)` → ``SELECT COUNT(*) FROM `db`.`table``` — need to handle db.table quoting.
- `getTableSize(db, table)` → `SELECT (data_length+index_length) FROM information_schema.TABLES WHERE table_schema=? AND table_name=?`
- `listRows(db, table, limit, offset)` → ``SELECT * FROM `db`.`table` LIMIT ? OFFSET ?`` — with backtick quoting for db and table.
- `listRowsWithId` — MySQL has no `ctid`; need PK detection: `SELECT column_name FROM information_schema.KEY_COLUMN_USAGE WHERE table_schema=? AND table_name=? AND constraint_name='PRIMARY' ORDER BY ordinal_position` — if PK exists, include PK in SELECT and use for delete. If no PK, fallback.
- `getTableColumns(db, table)` → `SELECT column_name FROM information_schema.COLUMNS WHERE table_schema=? AND table_name=? ORDER BY ordinal_position`
- `getTableStats(db, table)` → `SELECT table_rows, data_length, index_length, auto_increment FROM information_schema.TABLES WHERE table_schema=? AND table_name=?`
- `getAllTableStats(db)` → `SELECT table_name, table_rows, data_length, index_length FROM information_schema.TABLES WHERE table_schema=? ORDER BY table_name`
- `createTable(db, table, columns)` → ``CREATE TABLE `db`.`table` (col1 TEXT, col2 TEXT, ...)`` — MySQL TEXT type, no `public.` schema.
- `dropTable(db, table)` → ``DROP TABLE IF EXISTS `db`.`table````
- `truncateTable(db, table)` → ``TRUNCATE TABLE `db`.`table````
- `deleteRowById(db, table, pkCol, pkVal)` → ``DELETE FROM `db`.`table` WHERE `pkCol`=?`` — need PK column name.
- `deleteRowByAllColumns(db, table, values)` → ``DELETE FROM `db`.`table` WHERE `col1`=? AND `col2`=? LIMIT 1`` — fallback when no PK.
- `insertRow(db, table, values)` → ``INSERT INTO `db`.`table` (col1, col2) VALUES (?, ?)``
- `insertRows(db, table, columns, rows)` → batch insert.
- `ping()` → `SELECT 1`
- `getVersion()` → `SELECT VERSION()`
- `getMysqlMonitorData()` → `SHOW STATUS LIKE 'Connections'`, `SHOW STATUS LIKE 'Uptime'`, `SELECT VERSION()` — similar to PG monitor.

**`repository/MongoDatabaseRepository.java`** — no change; wrap as `MongoDatabaseEngine`.

**`repository/PostgresDatabaseRepository.java`** — no change.

### 4.4 Service Layer

**`service/DatabaseEngine.java`** — no change (already generic).

- **`service/MysqlDatabaseEngine.java`** — new, delegates to `MysqlDatabaseRepository`, implements `buildConnectionString`:
  ```java
  String buildConnectionString(String user, String pass, String db) {
    String host = resolveHost(); // from app.mysql.public-host or parsed from app.mysql.uri
    String encodedUser = uriEncode(user);
    String encodedPass = uriEncode(pass);
    String base = "mysql://" + encodedUser + ":" + encodedPass + "@" + host + "/" + db;
    if (publicTls) {
      String sslMode = publicSslmode; // REQUIRED, VERIFY_CA, VERIFY_IDENTITY
      return base + "?sslMode=" + sslMode;
    }
    return base;
  }
  // JDBC alternative: jdbc:mysql://host:3306/db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
  // or with TLS: jdbc:mysql://host:3306/db?sslMode=REQUIRED
  ```
  Host parsing: `jdbc:mysql://host:port/db?params` → extract `host:port` like PG does.

- **`service/ProvisioningService.java`** — refactor to handle MYSQL:
  - Inject `Optional<MysqlDatabaseEngine> mysqlEngine` + `Optional<MysqlDatabaseRepository> mysqlRepository` (like postgres).
  - `engineFor(MYSQL)` → `mysqlEngine.orElseThrow(() -> new ProvisioningException("MySQL is not enabled"))`
  - `provision(form)` → handle `MYSQL` like `POSTGRES`: `validateMysqlDatabaseName`, `validateMysqlUserName`, `engine.createUser`, `engine.createDatabase`, `engine.grantPrivileges`, store `MYSQL` in `ManagedDatabase`, `buildConnectionString` via engine. Cleanup on failure: `dropDatabase` + `dropUser` best-effort.
  - `resetPassword(MYSQL, db, form)` → delegate `updateUserPassword`.
  - `delete(MYSQL, db)` → `dropDatabase` + `dropUser` + `deleteByEngineTypeAndDbName`.
  - `listDatabases(MYSQL)` → per-engine list, filter `MYSQL_SYSTEM_DATABASES`.
  - `getDatabase(MYSQL, db)` → find by composite id.
  - `listUsers/revokeUser` → per-engine.
  - `createCollection/dropCollection` → only for `MONGO`; MYSQL throws `NameNotAllowedException`.
  - Lock key: `engine + ":" + dbName` already handles MYSQL.

- **`service/DatabaseNameValidator.java`** — add MySQL validation:
  ```java
  static final Set<String> MYSQL_SYSTEM_DATABASES = Set.of("information_schema", "mysql", "performance_schema", "sys");
  private static final int MAX_MYSQL_NAME_LENGTH = 64;
  private static final String MYSQL_PATTERN = "[a-z_][a-z0-9_]*"; // same as PG but max 64
  public void validateMysqlDatabaseName(String dbName) {
    requireValid(dbName, MYSQL_PATTERN, MAX_MYSQL_NAME_LENGTH, "MySQL database name must start with a letter or underscore and contain only lowercase letters, digits, and underscores");
    if (MYSQL_SYSTEM_DATABASES.contains(dbName.toLowerCase(ROOT))) throw new NameNotAllowedException(...);
    if (!dbName.equals(dbName.toLowerCase(ROOT))) throw new NameNotAllowedException("MySQL database name must be lowercase");
  }
  public void validateMysqlUserName(String userName) { /* same as PG but max 64 */ }
  public void validateMysqlTableName(String tableName) { /* same */ }
  ```

- **`service/MysqlExplorationService.java`** — new, mirrors `PostgresExplorationService`:
  - `listTables(db)` → `mysqlRepo.listTables(db)`
  - `getRows(db, table, page)` → `mysqlRepo.listRows(db, table, 50, offset)` with PK detection for delete.
  - `createTable(db, table, columns)` → validate, `mysqlRepo.createTable`.
  - `dropTable`, `truncateTable`, `deleteRow`, `insertRow` — similar to PG but with MySQL quoting and PK handling.
  - `writeAllRowsAsJson` for export.

- **`service/MysqlStatisticsService.java`** — new, mirrors `PostgresStatisticsService`:
  - `getDatabaseStats(db)` → `mysqlRepo.getAllTableStats(db)` + `getDatabaseSize(db)` via `information_schema`.
  - `getTableStats(db, table)` → `mysqlRepo.getTableStats(db, table)`.

- **`service/MysqlBackupService.java`** — new, mirrors `PostgresBackupService`:
  - `writeBackup(db, out)` → gzip'd JSON dump via `JdbcTemplate` paginated `SELECT *` (like PG, not `mysqldump` binary). `formatVersion:1`, `INSERT_BATCH_SIZE=1000`.
  - `restore(db, content, replace)` → parse gzip JSON, `DROP TABLE IF EXISTS` + `CREATE TABLE` + batch `INSERT`.

- **`service/MysqlMonitorService.java`** — new, mirrors `PostgresMonitorService`:
  - `getMysqlMonitorData()` → `SHOW STATUS`, `SHOW VARIABLES`, `SELECT VERSION()`.

### 4.5 Controller / View — Fully Separate

- **`controller/MysqlController.java`** — new, mirrors `PostgresController`:
  - `GET /mysql` → `engine-home` with `engine=MYSQL`
  - `POST /mysql/databases` → provision (with `engineType=MYSQL` hidden)
  - `GET /mysql/databases/{name}` → `database` with `engine=MYSQL`, tables list
  - `POST /mysql/databases/{name}/tables` → create table
  - `POST /mysql/databases/{name}/tables/{table}/delete` → drop table
  - `POST /mysql/databases/{name}/tables/{table}/truncate` → truncate
  - `POST /mysql/databases/{name}/tables/{table}/rows` → insert row
  - `POST /mysql/databases/{name}/tables/{table}/rows/delete` → delete row (by PK or all-columns)
  - `GET /mysql/databases/{name}/tables/{table}` → `table-rows` paginated
  - `GET /mysql/databases/{name}/tables/{table}/export` → JSON export
  - `GET /mysql/databases/{name}/stats` → `stats-mysql`
  - `GET /mysql/databases/{name}/reset` + `POST /mysql/databases/{name}/reset` → reset password
  - `GET /mysql/databases/{name}/delete` + `POST /mysql/databases/{name}/delete` → delete
  - `GET /mysql/databases/{name}/users` + `POST /mysql/databases/{name}/users/{user}/delete` → users
  - `GET /mysql/databases/{name}/backup` + `GET /mysql/databases/{name}/restore` + `POST /mysql/databases/{name}/restore` → backup/restore

- **`controller/ProvisionController.java`** — update to handle 3 engines:
  - `GET /provision` → chooser with 3 cards: MongoDB, PostgreSQL, MySQL
  - `GET /provision/mysql` → `provision-mysql.html`

- **`controller/DashboardController.java`** — add `mysqlCount`, `mysqlReachable` to model. No mixed list — three separate lists `mongoDatabases` + `postgresDatabases` + `mysqlDatabases`.

**Thymeleaf:**

| File | Change |
|---|---|
| `fragments/nav.html` | Add `MYSQL` branch: `th:if="${engine=='MYSQL'}"` show MySQL header, `↗ phpMyAdmin` link, `+ New MySQL DB` CTA. Back button already exists. |
| `index.html` | Split into three tables: `mongoDatabases` + `postgresDatabases` + `mysqlDatabases` with `engine-badge-mysql` (orange `#E67E22` or MySQL blue `#00758F`). Add MySQL CTA card. |
| `provision.html` | Chooser becomes 3 cards (row `col-md-4` each): MongoDB, PostgreSQL, MySQL with counts + reachable dots. |
| `provision-mysql.html` | New — thin wrapper around `provision-form.html` with `engineType=MYSQL` hidden, MySQL hints: `^[a-z_][a-z0-9_]*$`, max 64, lowercased. |
| `database.html` | Add `th:if="${database.engineType=='MYSQL'}"` show Tables (like PG), connection string label `MySQL URI` with `sslMode` hint. |
| `engine-home.html` | Already generic — handles MYSQL via `engine` param. |
| `tables.html` / `table-rows.html` | Reuse for MySQL (same as PG) — no new templates needed, or create `tables-mysql.html` if MySQL-specific columns needed. |
| `stats-mysql.html` | New — MySQL stats from `information_schema.TABLES` (table_rows, data_length, index_length). |
| `site.css` | Add `.engine-badge-mysql { background: rgb(0 117 143 / 0.12); color:#00758F; }` (MySQL dolphin blue) or `#E67E22` orange. `.provision-card-mysql { border-left-color: #00758F; }` |

### 4.6 Config

- **`config/MysqlConfig.java`** — new, mirrors `PostgresConfig`:
  ```java
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
  public class MysqlConfig {
    @Bean DataSource mysqlDataSource(@Value("${app.mysql.uri}") String uri, @Value("${MYSQL_ROOT_USER}") String user, @Value("${MYSQL_ROOT_PASSWORD}") String pass) {
      DriverManagerDataSource ds = new DriverManagerDataSource();
      ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
      ds.setUrl(uri);
      ds.setUsername(user);
      ds.setPassword(pass);
      return ds;
    }
    @Bean JdbcTemplate mysqlJdbcTemplate(DataSource mysqlDataSource) { return new JdbcTemplate(mysqlDataSource); }
  }
  ```

- **`config/PhpMyAdminProxyFilter.java`** — new, clone of `MongoExpressProxyFilter`/`AdminerProxyFilter`:
  - `PROXY_PREFIX="/phpmyadmin"`, `targetBase=http://127.0.0.1:9817`, same `NON_FORWARDED_HEADERS`, `HttpClient` (5s connect, 60s timeout), `Location` rewrite, 502/400 handling.
  - Alternative: `MysqlAdminerProxyFilter` if using Adminer for MySQL.

- **`config/SecurityConfig.java`** — update:
  ```java
  .requestMatchers("/mysql/**").hasRole("ADMIN") // for POST/DELETE
  .requestMatchers("/phpmyadmin/**").hasRole("ADMIN")
  .csrf(csrf -> csrf.ignoringRequestMatchers("/mongo-express/**","/adminer/**","/phpmyadmin/**"))
  ```

- **`config/MongoIndexInitializer.java`** — no change (already compound index on `(engineType, dbName)` handles MYSQL).

- **`config/MysqlHealthIndicator.java`** — new, mirrors `PostgresHealthIndicator`:
  ```java
  @Component
  @ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
  public class MysqlHealthIndicator implements HealthIndicator {
    public Health health() {
      try { mysqlRepo.ping(); return Health.up().withDetail("version", mysqlRepo.getVersion()).build(); }
      catch (Exception e) { return Health.down(e).build(); }
    }
  }
  ```

- **`config/ProvisionedDatabaseMetrics.java`** — update to handle MYSQL gauge: `provisioned.databases{engine="mysql"}`.

### 4.7 DDL (MySQL 8.4 — least-privilege)

```sql
-- provision (auto-commit, no transaction restriction)
CREATE DATABASE `myapp` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'myapp_user'@'%' IDENTIFIED BY 'MyStrongPass';
-- least-privilege grants (OWASP: minimal permissions, not ALL PRIVILEGES)
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER, CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE ON `myapp`.* TO 'myapp_user'@'%';
-- alternative simple: GRANT ALL PRIVILEGES ON `myapp`.* TO 'myapp_user'@'%';

-- reset (can be in transaction, but auto-commit anyway)
ALTER USER 'myapp_user'@'%' IDENTIFIED BY 'NewStrongPass';

-- delete
DROP DATABASE IF EXISTS `myapp`;
REVOKE ALL PRIVILEGES ON `myapp`.* FROM 'myapp_user'@'%';
DROP USER IF EXISTS 'myapp_user'@'%';

-- sizes
SELECT table_schema, SUM(data_length + index_length) AS size FROM information_schema.TABLES GROUP BY table_schema;
SELECT SUM(data_length + index_length) FROM information_schema.TABLES WHERE table_schema='myapp';

-- users with grants on db
SELECT user, host FROM mysql.db WHERE db='myapp';
-- or
SELECT user FROM mysql.user WHERE user NOT IN ('root','mysql.sys','mysql.session','mysql.infoschema');

-- tables
SELECT table_name FROM information_schema.TABLES WHERE table_schema='myapp' AND table_type='BASE TABLE' ORDER BY table_name;
-- stats
SELECT table_name, table_rows, data_length, index_length, auto_increment FROM information_schema.TABLES WHERE table_schema='myapp';
-- rows (paginated, backtick quoted)
SELECT * FROM `myapp`.`my_table` LIMIT 50 OFFSET 0;
-- PK detection
SELECT column_name FROM information_schema.KEY_COLUMN_USAGE WHERE table_schema='myapp' AND table_name='my_table' AND constraint_name='PRIMARY' ORDER BY ordinal_position;
-- or
SHOW KEYS FROM `myapp`.`my_table` WHERE Key_name='PRIMARY';
```

Identifier quoting mandatory — MySQL uses backticks `` `name` ``, not double quotes. Passwords parameterized (`?`). `CREATE DATABASE` uses `utf8mb4` + `utf8mb4_0900_ai_ci` (MySQL 8.4 default).

**Connection strings (enterprise):**

```
# MongoDB (existing)
mongodb://myapp_user:MyStrongPass@host:27017/myapp?authSource=myapp&tls=true

# PostgreSQL (existing)
postgresql://myapp_user:MyStrongPass@postgres.example.com:5432/myapp?sslmode=require&application_name=omnidb

# MySQL — without TLS (dev)
mysql://myapp_user:MyStrongPass@127.0.0.1:9816/myapp
jdbc:mysql://127.0.0.1:9816/myapp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC

# MySQL — with TLS (enterprise, MYSQL_PUBLIC_TLS=true)
mysql://myapp_user:MyStrongPass@mysql.example.com:3306/myapp?sslMode=REQUIRED
jdbc:mysql://mysql.example.com:3306/myapp?sslMode=REQUIRED&serverTimezone=UTC
# or with CA verification
mysql://myapp_user:MyStrongPass@mysql.example.com:3306/myapp?sslMode=VERIFY_IDENTITY
jdbc:mysql://mysql.example.com:3306/myapp?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/path/to/ca.crt&serverTimezone=UTC
```

`sslMode` per Connector/J docs: `DISABLED`, `PREFERRED`, `REQUIRED`, `VERIFY_CA`, `VERIFY_IDENTITY`. Enterprise should use `REQUIRED` minimum, `VERIFY_IDENTITY` when CA available.

---

## 5. Explorer — phpMyAdmin Proxy Spec

- **Image:** `phpmyadmin:5.2` (~150 MB) or `adminer:6.0.1-standalone` (41.6 MB) as light alternative.
- **Binding:** `127.0.0.1:9817:80` (phpMyAdmin) or `127.0.0.1:9817:8080` (Adminer) loopback only, like `mongo-express:38` and `adminer:42`.
- **Env:** `PMA_HOST=mysql`, `PMA_PORT=3306`, `PMA_USER=root`, `PMA_PASSWORD=root`, `UPLOAD_LIMIT=256M` (for phpMyAdmin) OR `ADMINER_DEFAULT_SERVER=mysql` (for Adminer).
- **Proxy:** `PhpMyAdminProxyFilter` cloning `MongoExpressProxyFilter:36`:
  - `PROXY_PREFIX="/phpmyadmin"`, `targetBase=http://127.0.0.1:9817`
  - Same `NON_FORWARDED_HEADERS`, same `HttpClient` (5s connect, 60s timeout), same `Location` rewrite, same 502/400 handling.
  - Behind `SecurityConfig` — only `hasRole('ADMIN')` reaches it.
- **Sidebar links (separate):**
  - Mongo context → `↗ Mongo Express` (`/mongo-express`)
  - Postgres context → `↗ Adminer` (`/adminer`)
  - MySQL context → `↗ phpMyAdmin` (`/phpmyadmin`) or `↗ Adminer (MySQL)` (`/mysql-adminer`)
  - Root → all three links under Explorers section

---

## 6. UI Plan — Provision-First + Engine-Scoped Nav with Back (fully separate, 3 engines)

### 6.1 Information Architecture

```
Current:  Dashboard (Mongo + Postgres) → /provision (2 cards) → /mongo/databases/{name} + /postgres/databases/{name}
Proposed: Dashboard → Provision (3-engine chooser) → Engine context (Mongo | Postgres | MySQL) — fully separate
         /mongo/databases/{name}  (Mongo only)
         /postgres/databases/{name} (Postgres only)
         /mysql/databases/{name} (MySQL only)
         Same name allowed: /mongo/databases/myapp, /postgres/databases/myapp, /mysql/databases/myapp coexist
```

**Route table (enterprise — prefix mandatory, no ambiguity):**

| Route | Purpose |
|---|---|
| `GET /` | Dashboard overview — three separate stat cards (Mongo / Postgres / MySQL) + three tables + recent activity per engine |
| `GET /provision` | **Provision tab** — three cards: `MongoDB` / `PostgreSQL` / `MySQL` (landing for "New database") |
| `GET /provision/mongo` | Mongo form (`engine=MONGO`) |
| `GET /provision/postgres` | Postgres form (`engine=POSTGRES`) |
| `GET /provision/mysql` | MySQL form (`engine=MYSQL`) |
| `POST /mongo/databases` | Provision Mongo DB |
| `POST /postgres/databases` | Provision Postgres DB |
| `POST /mysql/databases` | Provision MySQL DB |
| `GET /mongo` | Mongo engine home — lists only Mongo DBs |
| `GET /postgres` | Postgres engine home — lists only Postgres DBs |
| `GET /mysql` | MySQL engine home — lists only MySQL DBs |
| `GET /mongo/databases/{name}` | Mongo detail (collections, stats, backup, users) |
| `GET /postgres/databases/{name}` | Postgres detail (tables, stats, users) |
| `GET /mysql/databases/{name}` | MySQL detail (tables, stats, users) |
| `GET /mongo/databases/{name}/reset` | Mongo reset password |
| `GET /postgres/databases/{name}/reset` | Postgres reset password |
| `GET /mysql/databases/{name}/reset` | MySQL reset password |
| `GET /mongo-express` | Mongo explorer proxy |
| `GET /adminer` | Postgres explorer proxy |
| `GET /phpmyadmin` | MySQL explorer proxy |
| `GET /databases/{name}` | Legacy — 301 redirect to `/{engine}/databases/{name}` via metadata, or 404 if ambiguous |

### 6.2 Sidebar — Two Modes (fully separate, 3 engines)

**Mode A — Root (no engine selected):**

```
┌─ Sidebar ──────────────────┐
│ [icon] DB Manager          │
│ Main                       │
│  ○ Dashboard               │
│  ○ Activity  ○ Health      │
│  ○ Monitor                 │
│ Provision  ← NEW           │
│  ┌─ MongoDB ─┐ ┌─ Postgres┐│  ← three cards, click → enter engine context
│  │ 12 DBs    │ │ 3 DBs    ││
│  ├─ MySQL ───┤             │
│  │ 5 DBs     │             │
│  └───────────┘             │
│ Explorers                  │
│  ↗ Mongo Express           │
│  ↗ Adminer (Postgres)      │
│  ↗ phpMyAdmin (MySQL)      │
│ Quick Actions              │
│  + New database → /provision│
└────────────────────────────┘
```

**Mode B — Engine context (e.g. `/mysql/**`):**

```
┌─ Sidebar ──────────────────┐
│ ← Back to Dashboard  ← NEW │  ← sticky top, th:href="@{/}" + history.back() fallback
│ ─────────────────────────  │
│ ● MySQL  (or Mongo/Postgres)│  ← engine header with icon + badge count
│  ○ Databases               │  ← /mysql
│  ○ Stats                   │  ← engine-wide stats
│  ○ Users                   │  ← aggregated users
│  ───────────────────────── │
│  Explorer                  │
│   ↗ phpMyAdmin             │  ← only relevant explorer per engine
│  ───────────────────────── │
│  + New MySQL DB            │  ← contextual create → /provision/mysql
└────────────────────────────┘
```

**Back button spec:** Same as existing — `btn btn-sm btn-outline-light w-100` with `<i class="bi bi-arrow-left">` + "Back" — placed above engine header, `position: sticky; top: 0` inside `sidebar-nav`. `th:href="@{/}"` primary; `onclick="if(history.length>1){history.back();return false}"` as progressive enhancement.

### 6.3 Provision Tab (`/provision`)

- Layout: `container-xxl` with three equal cards (Bootstrap `row g-4` `col-md-4`):
  - **MongoDB card:** `bi-database` icon, count `mongoCount`, "Provision MongoDB" button → `/provision/mongo`, hint "Collections, documents, JSON export"
  - **PostgreSQL card:** `bi-server` icon, count `pgCount`, "Provision PostgreSQL" button → `/provision/postgres`, hint "Tables, rows, SQL"
  - **MySQL card:** `bi-hdd-stack` or `bi-database-fill` icon, count `mysqlCount`, "Provision MySQL" button → `/provision/mysql`, hint "Tables, rows, SQL"
- Each card shows `engine reachable` dot (`live-dot` / `live-dot-off` from `site.css:813-828`) via `HealthService` MySQL ping.
- Forms: reuse `provision.html:34-80` — separate templates `provision-mongo.html` / `provision-postgres.html` / `provision-mysql.html` with engine-specific hints: Mongo `[A-Za-z0-9_-]+`, PG `^[a-z_][a-z0-9_]*$` max 63, MySQL `^[a-z_][a-z0-9_]*$` max 64.

### 6.4 Templates to Add/Change

| File | Change |
|---|---|
| `fragments/nav.html` | Add `MYSQL` branch: `th:if="${engine=='MYSQL'}"` show MySQL header, `↗ phpMyAdmin` link, contextual `+ New MySQL DB`. Keep `head(title)` unchanged. |
| `index.html` | **Split into three tables** — `mongoDatabases` + `postgresDatabases` + `mysqlDatabases` with engine badges, no mixed list. Add MySQL CTA card at top. |
| `provision.html` | Becomes chooser (`/provision`) — three cards `MongoDB` / `PostgreSQL` / `MySQL` with counts + reachable dots + buttons to `/provision/mongo` / `/provision/postgres` / `/provision/mysql`. |
| `provision-mysql.html` | **New** — thin wrapper around `provision-form.html` with `engineType=MYSQL` hidden, MySQL hints. |
| `provision-form.html` | Already exists — ensure `engineType` hidden input handles MYSQL. |
| `database.html` | Add `th:if="${database.engineType=='MYSQL'}"` show Tables (like PG), connection string label `MySQL URI` with `sslMode` hint. |
| `engine-home.html` | Already generic — handles MYSQL via `engine` param. |
| `stats-mysql.html` | **New** — MySQL stats from `information_schema.TABLES` (table_rows, data_length, index_length). |
| `site.css` | Add `.engine-badge-mysql { background: rgb(0 117 143 / 0.12); color:#00758F; }` (MySQL blue) or orange `#E67E22`. `.provision-card-mysql { border-left-color: #00758F; }` |

### 6.5 CSS Delta (minimal, reuse tokens)

```css
.engine-badge-mysql { background: rgb(0 117 143 / 0.12); color:#00758F; } /* MySQL dolphin blue */
.provision-card-mysql { border-left-color: #00758F; }
```

Alternative orange: `#E67E22` for MySQL.

### 6.6 Controller / DTO Delta (UI wiring)

- `DashboardController:27` — add `mysqlCount`, `mysqlReachable` to model. No mixed list — three separate lists.
- `DatabaseController` → already split into `MongoController` + `PostgresController`, add `MysqlController`.
- `CreateDatabaseForm` — already has `DatabaseEngineType engineType` (`@NotNull`) — handles MYSQL.
- `DatabaseInfo` — already has `DatabaseEngineType engineType` — handles MYSQL.
- `AuthModelAdvice` — expose `engine` model attr for nav active state (already does).

### 6.7 Responsive

- `991px` collapsed sidebar (`site.css:1024-1057`) — back button collapses to icon-only, engine header hides text, provision cards stack `col-12`.
- `767px` top bar (`site.css:1059-1127`) — back button stays left of brand, provision cards single column (3 cards stack vertically).

---

## 7. Risks & Mitigations (enterprise)

- **SQL injection via db/user names** → strict validator + `quoteIdentifier()` (backticks) + parameterized passwords (`?`).
- **Orphaned users on failure** → best-effort cleanup (`try dropUser` in catch) per engine.
- **DROP DATABASE blocked by active connections** → MySQL `DROP DATABASE` succeeds even with connections (unlike PG which needs `pg_terminate_backend`); no extra step needed. Document difference.
- **Same name across engines** → **allowed** via composite key `engine:dbName`; routes are prefixed so no collision. Lock key is `engine:dbName`.
- **Password storage** — same risk as Mongo/PG (`storedPassword` encrypted via `EncryptionService` AES-256-GCM + KEK, already implemented for PG — reuse for MySQL).
- **Existing data** — old `ManagedDatabase` docs lack `engineType`; read as `MONGO` default, write back on next update (already handled).
- **MySQL volume** → mount `/var/lib/mysql` (not PG's `/var/lib/postgresql`), no `PGDATA` equivalent.
- **TLS** — enterprise: `require_secure_transport=ON` + `caching_sha2_password` in MySQL, `sslMode=REQUIRED` minimum, `VERIFY_IDENTITY` with CA. Document `MYSQL_PUBLIC_SSLMODE`.
- **Least privilege** — not `ALL PRIVILEGES`; use explicit `SELECT,INSERT,...` on `db.*`. Document trade-off vs `ALL PRIVILEGES` simplicity.
- **Row deletion without PK** → detect PK via `information_schema.KEY_COLUMN_USAGE`, fallback to all-columns `LIMIT 1` or reject with message "Table has no primary key — add PK or delete via phpMyAdmin". Document limitation.
- **Image size on 1GB VPS** — phpMyAdmin ~150 MB + MySQL ~260 MB = 410 MB extra (vs PG 80+41=121 MB). Total with Mongo (650m cap) may exceed 1GB. Recommend: run only needed engines via `compose.*.yaml` standalone, or use Adminer (41 MB) instead of phpMyAdmin for MySQL to save 110 MB. Document `docker compose -f compose.mysql.yaml up -d` for MySQL-only.
- **MySQL case sensitivity** → `lower_case_table_names` default 0 on Linux (case-sensitive). Enforce lowercase via validator to avoid confusion.
- **MySQL 8.4 `caching_sha2_password` vs old `mysql_native_password`** → Connector/J 8.0+ supports `caching_sha2_password` by default; ensure `allowPublicKeyRetrieval=true` in JDBC URL for non-TLS connections (required for RSA key exchange).

---

## 8. Testing Strategy

- Unit: `DatabaseNameValidator` (MySQL `^[a-z_][a-z0-9_]*$` max 64 vs PG max 63 vs Mongo `[A-Za-z0-9_-]+`), `MysqlDatabaseRepository` with mocked `JdbcTemplate`, `ProvisioningService` with fake engines, composite id handling, `quoteIdentifier` backticks.
- Integration: Testcontainers `MySQLContainer("mysql:8.4")` + `PostgreSQLContainer` + `MongoDBContainer` — provision/reset/delete lifecycle for each engine, **same name in all three engines** (e.g. `myapp` in MONGO, POSTGRES, MYSQL), connection string format with `sslMode`, least-privilege grants verification (`SELECT * FROM mysql.db WHERE db='myapp'`), table/row CRUD.
- Controller slice: `@WebMvcTest(MysqlController)` with `engineType=MYSQL` param, prefix routes `/mysql/databases/{name}`, legacy redirect.
- Verify `CREATE DATABASE` with `utf8mb4` charset — test that repository creates with correct charset/collation.
- Row deletion: test PK-based delete and fallback all-columns delete, and error when no PK.

---

## 9. Phased Roadmap

**Phase 1 — Core provisioning + phpMyAdmin proxy (1-2 days):** pom/compose/config (with MySQL 8.4), `DatabaseEngineType.MYSQL`, `MysqlDatabaseRepository` (backtick quoting, `caching_sha2_password`, least-privilege grants), `ManagedDatabase` composite id `engine:dbName` (already handles MYSQL), `ProvisioningService` triple-engine with separate lists, prefix routes `/mysql/databases/{name}`, form + dashboard three tables, provision-first nav with 3 cards. Same name allowed across all three.

**Phase 2 — Polish:** `DatabaseLockRegistry` composite key already handles MYSQL (no change), health check (`SELECT 1` for MySQL) with `ServerHealth` per engine (`mysqlReachable`), virtual-thread stats, docs/README update, TLS `sslMode` handling.

**Phase 3 — MySQL exploration (optional):** `listTables`, `getTableStats` via `information_schema`, paginated `SELECT * LIMIT/OFFSET`, export as JSON, PK-based row deletion. Separate from Mongo collections and PG tables.

**Phase 4 — Hardening (enterprise):** encrypt `storedPassword` (already done for PG — reuse for MySQL), `mysqldump` alternative via JDBC gzip JSON `formatVersion:1`, rate-limit per engine (already `IP:engine` handles MYSQL), metrics `provisioned.databases{engine="mysql"}`, `require_secure_transport` + `caching_sha2_password` hardening, `VERIFY_IDENTITY` CA support.

---

## 10. Open Questions — Resolved

1. Same DB name across engines allowed? **Yes** — composite key `engine:dbName`, prefix routes, lock key `engine:dbName` — now across 3 engines.
2. URL scheme: **Prefix mandatory** — `/mongo/databases/{name}` + `/postgres/databases/{name}` + `/mysql/databases/{name}`. Legacy `/databases/{name}` 301 redirects via metadata.
3. MySQL privileges: **Least-privilege** — `SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES,CREATE VIEW,SHOW VIEW,TRIGGER,CREATE TEMPORARY TABLES,LOCK TABLES,EXECUTE` on `db.*` (not `ALL PRIVILEGES`). Document `ALL PRIVILEGES` as simple alternative.
4. Public host: **Separate** — `MYSQL_PUBLIC_HOST` + `MYSQL_PUBLIC_TLS` + `MYSQL_PUBLIC_SSLMODE` (REQUIRED/VERIFY_CA/VERIFY_IDENTITY), keep `MONGODB_PUBLIC_HOST` and `POSTGRES_PUBLIC_HOST` separate. Connection strings include `sslMode`.
5. Explorer: **phpMyAdmin `5.2` default** (150 MB, MySQL-specific) with Adminer `6.0.1` as light alternative (41.6 MB, one-line compose swap). Keep Adminer for PG on 9815, phpMyAdmin for MySQL on 9817.
6. Metadata in MySQL later? **Keep in Mongo** for now — separate engines, no migration.
7. MySQL row deletion without PK? **PK-based with fallback** — detect PK, delete by PK; if no PK, all-columns `LIMIT 1` or error. Document limitation.
8. Image size on 1GB VPS? **Standalone compose** — `docker compose -f compose.mysql.yaml up -d` for MySQL-only, or use Adminer instead of phpMyAdmin to save 110 MB. Document.

Say `go Phase 1` and I'll implement it in the order above.

---

## 11. Research Sources (2026-08-28)

- MySQL 8.4 Reference Manual — `dev.mysql.com/doc/refman/8.4/en/create-database.html`, `create-user.html`, `grant.html`, `drop-database.html`, `drop-user.html`, `identifier-length.html`, `information-schema.html`, `charset.html`
- MySQL Connector/J 9.4/8.4 — `dev.mysql.com/doc/connector-j/en/connector-j-reference-configuration-properties.html` (sslMode, allowPublicKeyRetrieval, serverTimezone)
- Docker Hub — `hub.docker.com/_/mysql` (tags 8.4, 8.4.11, lts, amd64+arm64), `hub.docker.com/_/phpmyadmin` (5.2), `hub.docker.com/_/adminer` (6.0.1-standalone)
- Testcontainers — `java.testcontainers.org/modules/databases/mysql/` (`MySQLContainer("mysql:8.4")`, `jdbc:tc:mysql:8.4:///db`)
- Spring Boot 4.1.1 Reference — `docs.spring.io/spring-boot/reference/data/sql.html` (DataSource, JdbcTemplate, HikariCP, multiple DataSources)
- Spring Boot 4.1 Release Notes — `github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes`
- OWASP — `cheatsheetseries.owasp.org` (Database Security, Cryptographic Storage)
- MySQL Tutorial — `mysqltutorial.org/mysql-create-database/` (CREATE DATABASE syntax, charset/collation)

---

## 12. Appendix — MySQL vs PostgreSQL vs MongoDB Comparison

| Aspect | MongoDB 8 | PostgreSQL 18.6 | MySQL 8.4 LTS |
|---|---|---|---|
| **Identifier quoting** | No quoting (driver handles) | Double quotes `"name"` | Backticks `` `name` `` |
| **User identity** | `user@db` (per-DB) | `ROLE "user"` (cluster-wide) | `'user'@'%'` (user@host) |
| **Database creation** | Implicit on first write | `CREATE DATABASE "db" OWNER "user"` (cannot be in tx) | ``CREATE DATABASE `db` CHARACTER SET utf8mb4`` (auto-commit) |
| **Privileges** | `readWrite` on `db` | `CONNECT` on DB + `USAGE,CREATE` on schema + `ALTER DEFAULT PRIVILEGES` | `GRANT ... ON db.* TO 'user'@'%'` (db.* wildcard) |
| **System DBs** | `admin, local, config, mongodb_admin` | `postgres, template0, template1` | `information_schema, mysql, performance_schema, sys` |
| **Max name length** | 64 | 63 | 64 |
| **Validation pattern** | `[A-Za-z0-9_-]+` | `^[a-z_][a-z0-9_]*$` | `^[a-z_][a-z0-9_]*$` |
| **Row identity** | `_id` (ObjectId) | `ctid::text AS __pg_ctid` + `WHERE ctid = ?::tid` | PK column(s) or all-columns `LIMIT 1` |
| **Size query** | `dbStats` / `collStats` | `pg_database_size()` / `pg_total_relation_size()` | `SUM(data_length+index_length) FROM information_schema.TABLES` |
| **Drop with connections** | `dropDatabase` (no terminate) | `pg_terminate_backend` then `DROP DATABASE` | `DROP DATABASE` (succeeds even with connections) |
| **Auth plugin** | `SCRAM-SHA-256` | `scram-sha-256` | `caching_sha2_password` |
| **TLS param** | `&tls=true` | `?sslmode=require` | `?sslMode=REQUIRED` |
| **JDBC URL** | `mongodb://host/db` | `jdbc:postgresql://host/db` | `jdbc:mysql://host/db?useSSL=false&allowPublicKeyRetrieval=true` |
| **Docker image** | `mongo:8` (~700 MB) | `postgres:18.6-alpine` (~80 MB) | `mysql:8.4` (~260 MB) |
| **Explorer** | `mongo-express` (9814) | `adminer:6.0.1` (9815) | `phpmyadmin:5.2` (9817) or `adminer` |
| **Port** | 9812 | 9813 | 9816 |
| **Testcontainers** | `MongoDBContainer` | `PostgreSQLContainer("postgres:18.6-alpine")` | `MySQLContainer("mysql:8.4")` |
