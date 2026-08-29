# Environment Variables — OmniDB Manager

> Copy `.env.example` → `.env` and fill real values before `docker compose up -d`.
> All `*_URI` vars are **Manager → DB root links** (admin) on `127.0.0.1` — the manager runs on the same host as the DB containers and connects via loopback, **not** public DNS. They are used to `CREATE DATABASE / CREATE USER / GRANT`. They are **not** the per-database strings issued to your apps (those are built by `*DatabaseEngine.buildConnectionString()` using `*_PUBLIC_HOST` + `*_PUBLIC_SSLMODE` and shown in the UI).

## Quick Start

```bash
cp .env.example .env
# edit .env: change every `change-me-now`, set APP_ENCRYPTION_KEY=$(openssl rand -base64 32)
# *_URI defaults are already local (127.0.0.1) — Manager connects via loopback, not public DNS
# Prod: keep local *_URI, set *_PUBLIC_HOST + *_PUBLIC_SSLMODE for issued per-DB strings + TLS
docker compose up -d              # all engines
# or per engine:
docker compose -f compose.mongo.yaml up -d
docker compose -f compose.postgres.yaml up -d
docker compose -f compose.mysql.yaml up -d
```

**Loopback ports (not internet-exposed):** App `9811`, Mongo `127.0.0.1:9812`, Postgres `127.0.0.1:9813`, mongo-express `127.0.0.1:9814`, Adminer `127.0.0.1:9815`, MySQL `127.0.0.1:9816`, phpMyAdmin `127.0.0.1:9817`.

---

## 1. App Login — Web UI `http://127.0.0.1:9811/login` or `https://your-domain.com/login`

> **Access:** Local dev → `http://127.0.0.1:9811/login` (loopback, `SERVER_ADDRESS=127.0.0.1`). Public → `https://your-domain.com/login` via reverse proxy / Cloudflare Tunnel that forwards to `127.0.0.1:9811` (see §2 Condition B). The app itself always binds `127.0.0.1:9811`; public DNS is at the proxy, not the app.

| Variable | Default | Required | Where Used | Description |
|---|---|---|---|---|
| `APP_ADMIN_USERNAME` | `admin` | **Yes** | `application.yml:app.admin.username` → `SecurityConfig.userDetailsService()` | Single admin login. Stored as `BCrypt` in-memory user. Works for both local and public URL — same credentials. |
| `APP_ADMIN_PASSWORD` | `change-me-now` | **Yes** | same | **Must change.** Anyone with this can provision/delete all databases. |

## 2. Network / HTTPS

These are commented in `.env.example` — uncomment the block that matches your deployment.

| Variable | Default | Where Used | Description |
|---|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | `application.yml:server.address` | `127.0.0.1` = loopback only (secure, reach via reverse proxy / Cloudflare Tunnel). `0.0.0.0` = expose directly (not recommended for public). |
| `RATE_LIMIT_TRUST_XFF` | `false` | `application.yml:app.login-rate-limit.trust-x-forwarded-for` + `app.provision-rate-limit.trust-x-forwarded-for` | `false` = `X-Forwarded-For` ignored (prevents spoof). Set `true` **only** behind a trusted proxy that overwrites the header (Nginx / Cloudflare Tunnel), otherwise attacker bypasses rate limiting. |
| `SERVER_COOKIE_SECURE` | `false` | `application.yml:server.servlet.session.cookie.secure` | `false` for local `http://127.0.0.1:9811`. Set `true` behind TLS-terminating proxy or the login cookie is never sent → redirect loop (Phase 5 fix). |
| `SERVER_COOKIE_SAME_SITE` | `lax` | `application.yml:server.servlet.session.cookie.same-site` | `lax` = CSRF protection with top-level navigation allowed. Use `strict` in production. |

**Conditions in `.env.example`:**
- **A — Local dev (no TLS):** keep `SERVER_ADDRESS=127.0.0.1`, `RATE_LIMIT_TRUST_XFF=false`.
- **B — Behind reverse proxy / Cloudflare Tunnel (recommended for `https://`):** `SERVER_ADDRESS=127.0.0.1`, `RATE_LIMIT_TRUST_XFF=true`, `SERVER_COOKIE_SECURE=true`. App stays `127.0.0.1`, proxy forwards to `127.0.0.1:9811` and sets `X-Forwarded-Proto/For`. `application.yml` already has `forward-headers-strategy: framework`. Nginx example: `listen 443 ssl; proxy_pass http://127.0.0.1:9811; proxy_set_header X-Forwarded-Proto $scheme;`
- **C — Expose directly (not recommended):** `SERVER_ADDRESS=0.0.0.0`, `RATE_LIMIT_TRUST_XFF=false`.

## 3. MongoDB Engine

| Variable | Default | Where Used | Description |
|---|---|---|---|
| `MONGO_ENABLED` | `false` | `application.yml:app.mongo.enabled` | `true` = enable Mongo provisioning routes. `false` = routes disabled, health shows `disabled`. |
| `MONGODB_ROOT_USERNAME` | `root` | `compose.mongo.yaml:MONGO_INITDB_ROOT_USERNAME` + `application.yml:spring.mongodb.uri` fallback | Root/admin user for `mongo:27017`. Manager connects as this to run `createUser` / `createDatabase`. |
| `MONGODB_ROOT_PASSWORD` | `change-me-now` | same + `compose.mongo.yaml:ME_CONFIG_MONGODB_URL` | **Must change.** Also used by `mongo-express` internal URL. |
| `MONGODB_URI` | `mongodb://root:root@127.0.0.1:9812/?authSource=admin&maxPoolSize=10` | `application.yml:spring.mongodb.uri` → `MongoClient` | **Manager → Mongo root link (local, not public DNS).** Manager connects via loopback `127.0.0.1:9812` to provision databases. **Remote alternative:** `mongodb+srv://<clusterAdmin>:<password>@<cluster>.mongodb.net/?retryWrites=true&w=majority`. Empty `MONGODB_URI=` is normalized to absent → fallback to `127.0.0.1:9812` works. |
| `MONGO_EXPRESS_USERNAME` | `admin` | `compose.mongo.yaml:ME_CONFIG_BASICAUTH_USERNAME` | Basic auth for `http://127.0.0.1:9814` (proxied at `/mongo-express`). |
| `MONGO_EXPRESS_PASSWORD` | `change-me-now` | `compose.mongo.yaml:ME_CONFIG_BASICAUTH_PASSWORD` | **Must change.** |
| `MONGODB_PUBLIC_HOST` | `` (empty) | `application.yml:app.mongo-public-host` → `MongoDatabaseEngine.buildConnectionString()` | Host placed in **issued per-DB strings** shown in UI. Empty = derived from `MONGODB_URI` / `127.0.0.1:9812`. Set `mongo.example.com` when clients dial via domain/tunnel. Add `:port` only if non-`27017`. |
| `MONGODB_PUBLIC_TLS` | `false` | `application.yml:app.mongo-public-tls` | `false` = issued `mongodb://user:pass@host/db?authSource=db`. `true` = adds `&tls=true` (for `mongod --tls` or Nginx `stream { listen 27017 ssl; proxy_pass 127.0.0.1:9812; }`). |
| `MONGO_EXPRESS_BASE_URL` | `http://127.0.0.1:9814/mongo-express` | `application.yml:app.mongo-express.base-url` → `MongoExpressProxyFilter` | Internal URL for mongo-express proxy at `/mongo-express`. Don't change unless you move mongo-express. |

## 4. PostgreSQL Engine

| Variable | Default | Where Used | Description |
|---|---|---|---|
| `POSTGRES_ENABLED` | `false` | `application.yml:app.postgres.enabled` | `true` = enable Postgres provisioning. |
| `POSTGRES_ROOT_USER` | `root` | `compose.postgres.yaml:POSTGRES_USER` + `PostgresConfig` | Postgres superuser. Manager authenticates as this via `POSTGRES_URI`. |
| `POSTGRES_ROOT_PASSWORD` | `change-me-now` | same | **Must change.** |
| `POSTGRES_URI` | `jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10` | `application.yml:app.postgres.uri` → `PostgresConfig:HikariDataSource` | **Manager → Postgres root link (local, not public DNS).** Manager connects via loopback `127.0.0.1:9813` to do `CREATE DATABASE / CREATE ROLE / GRANT`. `sslmode=disable` + timeouts fix `enableSSL Read timed out` hang (Phase 4). **Remote alternative:** `jdbc:postgresql://pg.example.com:5432/postgres?sslmode=require` or `verify-full` with `&sslrootcert=./certs/ca.crt` + mount certs in `compose.postgres.yaml` (`ssl=on` + `hostssl` in `pg_hba.conf`). |
| `POSTGRES_PUBLIC_HOST` | `` | `application.yml:app.postgres.public-host` → `PostgresDatabaseEngine` | Host in **issued per-DB strings** (`postgresql://user:pass@host/db`). Empty = derived from `POSTGRES_URI`. Set `pg.example.com` for public. |
| `POSTGRES_PUBLIC_TLS` | `false` | `application.yml:app.postgres.public-tls` | Legacy symmetry flag. Real TLS is `POSTGRES_PUBLIC_SSLMODE`. |
| `POSTGRES_PUBLIC_SSLMODE` | `require` | `application.yml:app.postgres.public-sslmode` | `sslmode` in issued strings: `disable` (local), `require` (TLS without CA), `verify-full` (TLS + CA). For `verify-full` also set `POSTGRES_URI` with `sslrootcert` and mount `server.crt/key/ca.crt` in `compose.postgres.yaml`. |
| `ADMINER_BASE_URL` | `http://127.0.0.1:9815` | `application.yml:app.adminer.base-url` → `AdminerProxyFilter` at `/adminer` | Internal Adminer URL. Requires `ADMIN` role, loopback only. |

## 5. MySQL Engine

| Variable | Default | Where Used | Description |
|---|---|---|---|
| `MYSQL_ENABLED` | `false` | `application.yml:app.mysql.enabled` | `true` = enable MySQL provisioning. |
| `MYSQL_ROOT_PASSWORD` | `change-me-now` | `compose.mysql.yaml:MYSQL_ROOT_PASSWORD` + `MysqlConfig` | Root password (user is always `root`, do not set `MYSQL_ROOT_USER`). **Must change.** |
| `MYSQL_URI` | `jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000` | `application.yml:app.mysql.uri` → `MysqlConfig:HikariDataSource` | **Manager → MySQL root link (local, not public DNS).** Manager connects via loopback `127.0.0.1:9816`. `useSSL=false` + timeouts for local Docker. **Remote alternative:** `jdbc:mysql://mysql.example.com:3306/mysql?sslMode=REQUIRED&allowPublicKeyRetrieval=true&serverTimezone=UTC` or `VERIFY_IDENTITY` with `&trustCertificateKeyStoreUrl=file:./certs/ca.crt` + `MYSQL_PUBLIC_SSLMODE=VERIFY_IDENTITY`. |
| `MYSQL_PUBLIC_HOST` | `mysql.example.com` (commented) | `application.yml:app.mysql.public-host` | Host in issued `jdbc:mysql://user:pass@host/db` strings. |
| `MYSQL_PUBLIC_TLS` | `false` | `application.yml:app.mysql.public-tls` | Symmetry flag. |
| `MYSQL_PUBLIC_SSLMODE` | `REQUIRED` | `application.yml:app.mysql.public-sslmode` | `DISABLED` / `REQUIRED` (TLS without CA) / `VERIFY_IDENTITY` (TLS + CA). |
| `PHPMYADMIN_BASE_URL` | `http://127.0.0.1:9817` | `application.yml:app.phpmyadmin.base-url` → `PhpMyAdminProxyFilter` at `/phpmyadmin` | Internal phpMyAdmin URL. Requires `ADMIN` role, loopback only. |

## 6. Encryption at Rest

| Variable | Default | Where Used | Description |
|---|---|---|---|
| `APP_ENCRYPTION_KEY` | `` (empty) | `application.yml:app.encryption.key` → `EncryptionService` (AES-256-GCM) | **Critical.** Generate with `openssl rand -base64 32` (or `openssl rand -hex 32`). When set, per-database passwords in `mongodb_admin.managed_databases` are encrypted. When blank, stored **plaintext** (dev only). Changing the key after provisioning makes old passwords unreadable — generate once and back up securely. |

## 7. Manager → DB vs Issued Strings

| Link | Who Uses It | Example | Env Var |
|---|---|---|---|
| **Manager → DB (root)** | **Manager** as `root` on `127.0.0.1` to create DBs | `jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable` | `POSTGRES_URI` / `MYSQL_URI` / `MONGODB_URI` (local, never public DNS) |
| **Issued per-DB string** | **Your app** as per-DB user via public DNS | `postgresql://myapp_user:GENERATED_PASS@pg.example.com:5432/myapp?sslmode=require` | Built by `*DatabaseEngine.buildConnectionString()` using `*_PUBLIC_HOST` + `*_PUBLIC_SSLMODE` |

Never give the root `*_URI` to your apps. `*_URI` stays `127.0.0.1` (manager and DB on same host via Docker); `*_PUBLIC_HOST` is what your apps dial.

## 8. Local vs Production

> `*_URI` is always `127.0.0.1` — Manager and DB run on the same host via Docker. Public DNS goes in `*_PUBLIC_HOST`, not `*_URI`.

**Local Docker (loopback, no TLS):**
```env
MONGO_ENABLED=true
MONGODB_URI=mongodb://root:root@127.0.0.1:9812/?authSource=admin&maxPoolSize=10
POSTGRES_ENABLED=true
POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10
MYSQL_ENABLED=true
MYSQL_URI=jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000
# No *_PUBLIC_HOST needed — issued strings use 127.0.0.1
```

**Production (same host, public TLS for issued strings):**
```env
# Manager still connects via loopback — DO NOT change *_URI to public DNS
MONGO_ENABLED=true
MONGODB_URI=mongodb://root:root@127.0.0.1:9812/?authSource=admin&maxPoolSize=10
MONGODB_PUBLIC_HOST=mongo.example.com
MONGODB_PUBLIC_TLS=true
POSTGRES_ENABLED=true
POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10
POSTGRES_PUBLIC_HOST=pg.example.com
POSTGRES_PUBLIC_SSLMODE=require
# For verify-full issued strings: POSTGRES_PUBLIC_SSLMODE=verify-full + mount certs in compose.postgres.yaml
MYSQL_ENABLED=true
MYSQL_URI=jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000
MYSQL_PUBLIC_HOST=mysql.example.com
MYSQL_PUBLIC_SSLMODE=REQUIRED
SERVER_COOKIE_SECURE=true
RATE_LIMIT_TRUST_XFF=true
```
Plus uncomment TLS volumes/command in `compose.postgres.yaml` and mount `server.crt/key/ca.crt` for `verify-full`.

**Remote DB (Manager on different host than DB) — only then change `*_URI`:**
```env
# e.g. Atlas or RDS — Manager dials remote host
MONGODB_URI=mongodb+srv://<clusterAdmin>:<password>@<cluster>.mongodb.net/?retryWrites=true&w=majority
POSTGRES_URI=jdbc:postgresql://pg.example.com:5432/postgres?sslmode=require
MYSQL_URI=jdbc:mysql://mysql.example.com:3306/mysql?sslMode=REQUIRED&allowPublicKeyRetrieval=true&serverTimezone=UTC
```
