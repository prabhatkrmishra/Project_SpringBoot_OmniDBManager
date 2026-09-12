# Environment Variables — OmniDB Manager

> Copy `.env.example` → `.env` and fill real values before `docker compose up -d`.
> Manager + DBs all on `127.0.0.1` same VPS — set `*_ISSUED_HOST` to the host your apps dial for per-DB strings.

## Quick Start

```bash
cp .env.example .env
# edit .env: change every `change-me-now`, set APP_ENCRYPTION_KEY=$(openssl rand -base64 32)
# No URI vars needed locally — Manager connects via loopback by default
# Prod: set *_ISSUED_HOST to your VPS Tailscale IP / domain for issued per-DB strings
docker compose up -d              # all engines
# or per engine:
docker compose -f compose.mongo.yaml up -d
docker compose -f compose.postgres.yaml up -d
docker compose -f compose.mysql.yaml up -d
```

**Loopback ports (not internet-exposed):** App `9811`, Mongo `127.0.0.1:9812`, Postgres `127.0.0.1:9813`, mongo-express `127.0.0.1:9814`, Adminer `127.0.0.1:9815`, MySQL `127.0.0.1:9816`, phpMyAdmin `127.0.0.1:9817`, PgBouncer `127.0.0.1:6432`.

---

## 1. App Login — Web UI `http://127.0.0.1:9811/login`

| Variable | Default | Required | Where Used | Description |
|---|---|---|---|---|
| `APP_ADMIN_USERNAME` | `admin` | **Yes** | `application.yml:app.admin.username` → `SecurityConfig.userDetailsService()` | Single admin login. Stored as `BCrypt` in-memory user. |
| `APP_ADMIN_PASSWORD` | `change-me-now` | **Yes** | same | **Must change.** Anyone with this can provision/delete all databases. |
| `APP_ENCRYPTION_KEY` | `` (empty) | **Yes in prod** | `application.yml:app.encryption.key` → `EncryptionService` (AES-256-GCM) | Generate with `openssl rand -base64 32`. When blank, stored **plaintext** (dev only). |

## 2. MongoDB Engine

| Variable | Default | Required | Where Used | Description |
|---|---|---|---|---|
| `MONGO_ENABLED` | `false` | **Yes** | `application.yml:app.mongo.enabled` | `true` = enable Mongo provisioning routes. |
| `MONGODB_ISSUED_HOST` | `` (empty) | **Yes in prod** | `application.yml:app.mongo.issued-host` → `MongoDatabaseEngine.buildConnectionString()` | Host baked into **issued per-DB strings** apps dial. Empty = `127.0.0.1:9812` for local dev. Set to VPS Tailscale IP / domain when apps live on other servers. Use `host:<custom-port>` form to serve it on a non-standard public port (see `deploy/nginx.conf.example`). |
| `MONGODB_ROOT_PASSWORD` | `change-me-now` | **Yes if enabled** | `compose.mongo.yaml:MONGO_INITDB_ROOT_PASSWORD` + `spring.mongodb.uri` | **Must change.** Root for `mongo:27017`. |

## 3. PostgreSQL Engine

| Variable | Default | Required | Where Used | Description |
|---|---|---|---|---|
| `POSTGRES_ENABLED` | `false` | **Yes** | `application.yml:app.postgres.enabled` | `true` = enable Postgres provisioning (+ PgBouncer sidecar, managed). |
| `POSTGRES_ISSUED_HOST` | `` (empty) | **Yes in prod** | `application.yml:app.postgres.issued-host` → `PostgresDatabaseEngine` | **DNS only, no `:port`** — host in issued strings. Empty = `127.0.0.1` (local dev, ports ignored). Set to `pg.example.com` when apps on other servers. Legacy `host:port` is stripped with a WARN — use the two port vars below. |
| `POSTGRES_ISSUED_PORT` | `5432` | No | `application.yml:app.postgres.issued-port` → `PostgresDatabaseEngine.resolveDirectHost()` | Public direct port `A` → `127.0.0.1:9813` (migrations/admin). Non-standard prod e.g. `27431`. Ignored when `POSTGRES_ISSUED_HOST` blank. Must be `1-65535`. |
| `PGBOUNCER_ISSUED_PORT` | `6432` | No | `application.yml:app.pgbouncer.issued-port` → `PostgresDatabaseEngine.resolvePooledHost()` | Public pooled port `B` → `127.0.0.1:6432` (app/workers). Non-standard prod e.g. `27432`. Ignored when host blank. Must be `1-65535`. |
| `POSTGRES_ROOT_PASSWORD` | `change-me-now` | **Yes if enabled** | `compose.postgres.yaml:POSTGRES_PASSWORD` + `PostgresConfig` | **Must change.** Superuser for DDL. |
| `PGBOUNCER_ADMIN_PASSWORD` | `change-me-now` | **Yes if enabled** | `compose.postgres.yaml:pgbouncer-init` | **Must change.** Pooler admin (no host folder, static wildcard). |
| `PGBOUNCER_STATS_PASSWORD` | `change-me-now` | **Yes if enabled** | same | Monitor `stats_users` for `SHOW` only. |
| `PGBOUNCER_AUTH_PASSWORD` | `change-me-now` | **Yes if Postgres enabled** | `application.yml:app.pgbouncer.auth-password` → `PostgresDatabaseEngine.installPooledAuth` + `compose.postgres.yaml:pgbouncer-init` userlist | **Must change.** SCRAM credential for the pooler `auth_user` (`pgbouncer_auth`). Written plain-text into the pooler `userlist.txt` (required form for `auth_type=scram-sha-256`); file stays 600/pooler-owned in a named volume. Must match the running pooler container or every pooled login fails. Avoid `"`/`\` in this value. |

> Pooling is per-database at provision time (**Route via PgBouncer** checkbox, stored as `pooled`). No toggle after — pooled strings use `PGBOUNCER_ISSUED_PORT` (default `6432`), direct use `POSTGRES_ISSUED_PORT` (default `5432`). When `POSTGRES_ISSUED_HOST` blank, both resolve to `127.0.0.1:9813` / `127.0.0.1:6432` for local dev.

## 4. MySQL Engine

| Variable | Default | Required | Where Used | Description |
|---|---|---|---|---|
| `MYSQL_ENABLED` | `false` | **Yes** | `application.yml:app.mysql.enabled` | `true` = enable MySQL provisioning. |
| `MYSQL_ISSUED_HOST` | `` (empty) | **Yes in prod** | `application.yml:app.mysql.issued-host` | Host in issued strings. Empty = `127.0.0.1:9816`. Use `host:<custom-port>` form for a non-standard public port. |
| `MYSQL_ROOT_PASSWORD` | `change-me-now` | **Yes if enabled** | `compose.mysql.yaml:MYSQL_ROOT_PASSWORD` + `MysqlConfig` | **Must change.** Root password (user always `root`). |

## 5. Network / HTTPS (advanced)

| Variable | Default | Where Used | Description |
|---|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | `application.yml:server.address` | Loopback only. `0.0.0.0` = expose directly (not recommended). |
| `RATE_LIMIT_TRUST_XFF` | `false` | `application.yml:app.*.trust-x-forwarded-for` | Set `true` only behind trusted proxy. |

## 6. Encryption at Rest

See §1 `APP_ENCRYPTION_KEY` — changing the key after provisioning makes old passwords unreadable.

## 7. Manager → DB vs Issued Strings

| Link | Who Uses It | Example | Env Var |
|---|---|---|---|
| **Manager → DB (root)** | **Manager** as `root` on `127.0.0.1` to create DBs | `jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable` | Managed default — `OVERRIDE_*_URI` only for remote (see §10a) |
| **Issued per-DB string** | **Your app** as per-DB user via TCP | `postgresql://myapp_user:GENERATED_PASS@pg.example.com:5432/myapp?sslmode=require` | Built by `*DatabaseEngine.buildConnectionString()` using `*_ISSUED_HOST` |

## 8. Local vs Production

> Local needs 0 URI vars. Remote only then use `OVERRIDE_`.

**Local Docker (loopback, no TLS):**
```env
MONGO_ENABLED=true
POSTGRES_ENABLED=true
MYSQL_ENABLED=true
# No *_ISSUED_HOST needed — issued strings use 127.0.0.1
```

**Production (same host, apps on other servers):**
```env
MONGO_ENABLED=true
MONGODB_ISSUED_HOST=mongo.example.com
POSTGRES_ENABLED=true
POSTGRES_ISSUED_HOST=pg.example.com
MYSQL_ENABLED=true
MYSQL_ISSUED_HOST=mysql.example.com
```

## 10. Overrides

### 10a. Remote connections (`_URI`, `_ISSUED_HOST` already in §2-4, `_TLS`/`_SSLMODE`)

| Variable | Default | Where Used | Description |
|---|---|---|---|
| `OVERRIDE_MONGODB_URI` | `mongodb://root:root@127.0.0.1:9812/...` | `spring.mongodb.uri` | Manager → Mongo root link. Only set for Atlas/remote. |
| `OVERRIDE_POSTGRES_URI` | `jdbc:postgresql://127.0.0.1:9813/postgres?...` | `app.postgres.uri` | Manager → Postgres root link. Only for remote. |
| `OVERRIDE_MYSQL_URI` | `jdbc:mysql://127.0.0.1:9816/mysql?...` | `app.mysql.uri` | Manager → MySQL root link. Only for remote. |
| `OVERRIDE_MONGODB_TLS` | `false` | `app.mongo.tls` | Adds `&tls=true` to issued Mongo strings. |
| `OVERRIDE_POSTGRES_SSLMODE` | `require` | `app.postgres.sslmode` | `disable` / `require` / `verify-full` in issued strings. |
| `OVERRIDE_MYSQL_TLS` | `false` | `app.mysql.tls` | Adds `?sslMode=REQUIRED` to issued MySQL strings. |

> `_TLS` vs `_SSLMODE` is intentional, not inconsistency: `_TLS` is a boolean toggle (Mongo/MySQL), `_SSLMODE` is an enum (Postgres only).

### 10b. Tuning (`OVERRIDE_PGBOUNCER_*` + pooler passwords)

| Variable | Default | Where Used | Description |
|---|---|---|---|
| `OVERRIDE_PGBOUNCER_PORT` | `6432` | `app.pgbouncer.port` + `compose.postgres.yaml:pgbouncer.ports` | Loopback `127.0.0.1:6432`. Same Docker network as `postgres` (`postgres:5432` internally). |
| `OVERRIDE_PGBOUNCER_POOL_MODE` | `transaction` | `pgbouncer.ini:pool_mode` | Locked to `transaction`. |
| `OVERRIDE_PGBOUNCER_MAX_CLIENT_CONN` | `1000` | `pgbouncer.ini:max_client_conn` | Leave headroom for admin (`Hikari maxPool 5`). |
| `OVERRIDE_PGBOUNCER_DEFAULT_POOL_SIZE` | `5` | `pgbouncer.ini:default_pool_size` | Conservative §17 budget: `max_connections`(100) − admin(5) − OmniDB(10) leaves ~85 for tenants. Was `25` (50 DBs × 25 = 1250 backends). |
| `OVERRIDE_PGBOUNCER_RESERVE_POOL_SIZE` | `2` | `pgbouncer.ini:reserve_pool_size` | Burst headroom per DB. |
| `OVERRIDE_PGBOUNCER_RESERVE_POOL_TIMEOUT` | `3` | `pgbouncer.ini:reserve_pool_timeout` | Seconds to use reserve pool. |
| `OVERRIDE_PGBOUNCER_MAX_DB_CONNECTIONS` | `10` | `pgbouncer.ini:max_db_connections` | Hard cap per DB (was `50`). |
| `DATABASE_PROXY_ENABLED` | `false` | `database.proxy.enabled` | S-06 gate: keep `false` until S-05 evidence + prod hostnames + `:15432` cert + NSG single-port approval. Two-port Nginx stays authoritative. |
| `DATABASE_PROXY_PORT` | `15432` | `database.proxy.port` | Single public TCP port (target). |
| `DATABASE_PROXY_DIRECT_HOST` | `` | `database.proxy.direct-host` | e.g. `db.example.com`. Must differ from pooled-host when enabled. |
| `DATABASE_PROXY_POOLED_HOST` | `` | `database.proxy.pooled-host` | e.g. `pool.example.com`. Unknown SNI is rejected, never falls back. |
| `PGBOUNCER_ADMIN_PASSWORD` | `change-me-now` | `compose.postgres.yaml:pgbouncer-init` + `app.pgbouncer.admin-password` | **Must change when Postgres enabled.** Never logged. |
| `PGBOUNCER_STATS_PASSWORD` | `change-me-now` | same | `stats_users` for `SHOW` only. |
| `PGBOUNCER_AUTH_PASSWORD` | `change-me-now` | `compose.postgres.yaml:pgbouncer-init` userlist + `app.pgbouncer.auth-password` | **Must change when Postgres enabled.** Pooler `auth_user` SCRAM credential; must match the running container. |

*Privilege scoping:* `admin_users` (reload) ≠ `stats_users` (SHOW only) ≠ `pgbouncer_auth` (`LOGIN` + `CONNECT` on pooled DBs + `EXECUTE` on `pgbouncer.user_lookup` only — never superuser, never table grants).
