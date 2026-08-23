# MongoDB Server Manager

A self-hosted, Atlas-style MongoDB provisioning service. Sign in with an admin
account, create databases with dedicated per-database users, hand the
connection string to your external applications, and later reset
the password or delete the database (drop DB + drop user) — all from a small
Spring MVC web UI.

> If you like this project, give it a [⭐ on GitHub](https://github.com/prabhatkrmishra/Project_SpringBoot_MongoDBServer) — it keeps me going!

## Features

- **Spring Security form login** — every page requires authentication; only the
  admin (credentials from `.env`) can create, reset, or delete databases.
- **Brute-force protection** — login is rate-limited per IP + username
  (5 attempts / 15 min, configurable via `app.login-rate-limit.*`); excess
  attempts get HTTP 429 with a `Retry-After` header.
- **Atlas-style provisioning** — each database gets a dedicated MongoDB user
  with `readWrite` scoped to exactly that database.
- **Connection strings on demand** — the connection string (with password) is
  shown after creation/reset and re-derived from stored provisioning metadata,
  so it stays viewable on the database detail page at any time. That means the
  per-database password is stored (plaintext) in the `mongodb_admin` metadata
  database — protect that database like you protect `.env`.
- **Password rotation** — reset a database user's password; the old password
  stops working immediately.
- **Deletion** — drops the database and its dedicated user.
- **Explorer** — browse databases, collections, and paginated documents.
- **Export** — download any collection page as a JSON file.
- **Audit trail** — every provision/reset/delete is recorded; view the last 10 on
  the dashboard or the full paginated history at `/activity`.
- **MongoDB + mongo-express** — all run via Docker Compose.
- **mongo-express web UI** — [mongo-express](https://github.com/mongo-express/mongo-express)
  is bundled in the stack, but only reachable through the app at `/mongo-express`
  (sidebar link), behind the same login; its container is loopback-bound with no
  public port.
- **Atlas profile** — point `MONGODB_URI` at any MongoDB deployment.

## Prerequisites

- JDK 25
- Docker Desktop (or any Docker daemon) with Docker Compose
- No global Maven needed — the project ships the Maven wrapper (`mvnw`).

## Quick start (local)

1. Create the environment file (once):

   ```bash
   cp .env.example .env
   ```

   Edit `.env`: `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` are the web app
   login; `MONGODB_ROOT_USERNAME` / `MONGODB_ROOT_PASSWORD` are the MongoDB root
   credentials used by the app to provision databases; `MONGO_EXPRESS_*` protect
   the mongo-express UI.

2. Start MongoDB and mongo-express:

   ```bash
   docker compose up -d
   ```

   - App: http://localhost:9811
   - mongo-express: no direct URL — sign in to the app and open **Mongo Express**
     from the sidebar (`/mongo-express`); its basic auth uses `MONGO_EXPRESS_*`.

3. Run the app:

   ```bash
   ./mvnw spring-boot:run
   ```

4. Sign in at http://localhost:9811/login with `APP_ADMIN_USERNAME` /
   `APP_ADMIN_PASSWORD`, then **Provision a database**. Copy the shown
   connection string into your application's configuration — it stays viewable
   on the database detail page if you need it again. External
   applications use that connection string to read/write only that database.

### Run from the compiled jar (no repo needed)

The jar is only the web layer — it **connects to** MongoDB, it does not start
it. Running `java -jar` on a fresh server with nothing else up will boot the app
but every page fails: provisioning errors without MongoDB.

1. Build the jar once (from the repo):

   ```bash
   ./mvnw clean package
   ```

2. Copy `target/mongodbserver-0.0.1-SNAPSHOT.jar`, `.env`, and `compose.yaml` to
   the server.

3. Start the backing services:

   ```bash
   docker compose up -d
   ```

4. Run the app:

   ```bash
   java -jar mongodbserver-0.0.1-SNAPSHOT.jar
   ```

The jar connects to: MongoDB at `spring.data.mongodb.uri` (default
`mongodb://<root>:<pass>@127.0.0.1:9812/?authSource=admin` from `.env`) and the
bundled mongo-express at `127.0.0.1:9814` (only needed for the `/mongo-express`
sidebar UI; without it that page returns 502). mongo-express hosts are not
configurable via `.env` yet — fixed at `127.0.0.1` in `application.yml`.

### Memory footprint

The auto-deploy script (`deploy/deploy.sh`) starts the jar with memory-tuned
JVM options, measured at roughly **205 MB RSS** on JDK 25 (vs ~360 MB with a
plain `-Xms256m -Xmx512m`):

```bash
java -Xms64m -Xmx256m -XX:+UseSerialGC -XX:+UseCompactObjectHeaders \
     -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=96m -Xss512k \
     -jar mongodbserver-*.jar
```

- `-XX:+UseSerialGC` has the lowest native overhead for small heaps.
- `-XX:+UseCompactObjectHeaders` (JDK 25+) trims every object on the heap.
- The caps bound metaspace, JIT code cache and thread stacks.

Override by exporting `JAVA_OPTS` before running `deploy.sh`. Notes for
**1 GB-RAM servers**, where mongod shares the budget with the JVM:

- `compose.yaml` already pins the guardrails: WiredTiger cache at 256 MB,
  `--maxIncomingConnections 500` (each connection costs mongod ~0.5–1 MB of
  RAM), a 64k nofile ulimit, and a 650 MB container memory cap. Raise them
  together if you move to a bigger box.
- Consider removing the `mongo-express` service entirely — it is optional
  (the app works without it; `/mongo-express` just returns 502).
- Restores of very large backup files need heap headroom: with `-Xmx256m`
  keep uploaded backups well under the 256 MB multipart limit, or raise
  `-Xmx` while tuning the rest of the stack down.

## Using the provisioned database from your application

Example (Node.js):

```js
const { MongoClient } = require("mongodb");
const client = new MongoClient("mongodb://myapp_user:MyStrongPass@127.0.0.1:9812/myapp?authSource=myapp");
await client.connect();
```

The user can only read/write its own database; the password can be rotated by
the admin at any time.

### Your application connects **directly** to MongoDB

This app is only the **control plane**: it provisions databases, issues
per-database credentials, and rotates/deletes them. It is **not** a proxy and
never sits in the data path.

- The connection string points straight at MongoDB (`127.0.0.1:9812` locally,
  or your Atlas host when the `atlas` profile is active), so your application
  talks to MongoDB directly over the Mongo wire protocol.
- The provisioned user authenticates against its own database (explicit
  `?authSource=<db>`), so the string works as-is in every driver — no extra
  configuration, no calls back to this app.
- After provisioning, this app is not involved in your application's traffic.
  It is needed again only when an admin resets a password or deletes a database.

## Atlas (or any external MongoDB)

Set `MONGODB_URI` in `.env` to a deployment URI with root privileges, e.g.:

```
MONGODB_URI=mongodb+srv://root:root@cluster0.xxxxx.mongodb.net/?authSource=admin
```

Then run with the `atlas` profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=atlas
```

Or, from the jar:

```bash
java -jar mongodbserver-0.0.1-SNAPSHOT.jar --spring.profiles.active=atlas
```

Connection strings shown in the UI are derived from the active
`spring.mongodb.uri` host, so external apps connect to the same deployment.

## Releases & deployment

**Releasing** (`.github/workflows/release.yml`): tag a release and push it —
GitHub builds the jar, sets the version from the tag, generates a changelog
from commits since the previous tag, and attaches the jar to a GitHub Release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

(Or trigger the **Release** workflow manually with a tag input.)

**Auto-deploy** (`deploy/deploy.sh`): on the VPS, run once:

```bash
bash deploy/setup-cron.sh
```

A cron job then checks the latest GitHub Release every 5 minutes, downloads the
new jar, stops the old process (tmux session `mongodbserver`) and starts the
new one. Logs: `~/mongodbserver/deploy.log`.

## Architecture

```
Controller  →  Service  →  Repository (MongoDB Java driver / Spring Data)
     │            │
     └──── Thymeleaf views (server-rendered, th:text only)
```

- `ProvisioningService` — lifecycle: provision / reset / delete / list.
- `ExplorationService` — read-only browsing with bounded pagination (50/page)
  and JSON export.
- `MongoDatabaseRepository` — driver gateway for user + database administration.
- `ManagedDatabaseRepository` — Spring Data metadata in the `mongodb_admin`
  database (stores the per-database user's password so connection strings can
  be re-derived).
- `SecurityConfig` — form login, CSRF on, `@PreAuthorize` + route matchers for
  admin-only writes.
- `LoginRateLimitFilter` — in-process fixed-window brute-force protection on
  the login form, running ahead of the security chain.
- `MongoExpressProxyFilter` — reverse-proxies the bundled mongo-express UI at
  `/mongo-express` behind the app's authentication.
- `MongoIndexInitializer` — creates the audit-trail index on startup.
- `AdminCredentialsGuard` — refuses to start with default `admin`/`admin`
  credentials under the `atlas` profile.

Naming is validated and restricted to URL-safe characters; system databases
(`admin`, `local`, `config`, `mongodb_admin`) are protected.

## Tests

```bash
./mvnw test
```

- Unit tests for the validator, password generator, both services, and the
  login rate-limiter (filter + in-memory counter).
- `@WebMvcTest` slices for the controllers (auth, CSRF, validation, error
  handling).
- Testcontainers-backed tests (real MongoDB with auth) for the driver
  repository and the full provision/reset/delete lifecycle, including a login
  rate-limit burst check. These are skipped automatically when Docker is
  unavailable.

## Project layout

```
compose.yaml                      # MongoDB + mongo-express (mongo-express loopback-bound)
.env / .env.example               # credentials (gitignored)
src/main/java/com/pkmprojects/mongodbserver
  config/                         # Security, rate limiting, mongo-express proxy, properties, Clock
  controller/                     # Login, Dashboard, Database, Collection, Activity
  dto/                            # Form + view objects (CreateDatabaseForm, DatabaseInfo, ...)
  error/                          # Domain exceptions + global handler
  model/                          # AuditEvent, ManagedDatabase (stores the per-db user password)
  repository/                     # Driver gateway + Spring Data metadata repos
  security/                       # Password generator
  service/                        # Provisioning, Exploration, name validation
src/main/resources
  application.yml                 # local defaults (spring.mongodb.*, rate-limit, proxy)
  application-atlas.yml           # MONGODB_URI-driven profile
  static/
    css/site.css                  # UI styling
    js/app.js                     # copy-to-clipboard, confirm, toggle-password helpers
    favicon.ico
  templates/                      # Thymeleaf views (login, index, database, collections,
    fragments/                    #   activity, reset-password, delete-confirm, error)
```

## Third-party libraries & credits

This project is built on the shoulders of the following open-source software:

| Library | Used for | License |
| --- | --- | --- |
| [Spring Boot](https://spring.io/projects/spring-boot) 4.1 / Spring Framework 7 | Web, security, data, validation starters | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [Spring Security](https://spring.io/projects/spring-security) | Form login, CSRF, method security, rate-limit filter ordering | Apache-2.0 |
| [Spring Data MongoDB](https://spring.io/projects/spring-data-mongodb) | Repository metadata + driver gateway | Apache-2.0 |
| [MongoDB Java Driver](https://www.mongodb.com/docs/drivers/java/) | Direct database/user administration | Apache-2.0 |
| [Thymeleaf](https://www.thymeleaf.org/) + `thymeleaf-extras-springsecurity6` | Server-rendered views | Apache-2.0 |
| [springboot4-dotenv](https://github.com/paulschwarz/spring-dotenv) | `.env` loading | MIT |
| [Bootstrap](https://getbootstrap.com/) 5.3.8 (WebJar) | UI styling | MIT |
| [Bootstrap Icons](https://icons.getbootstrap.com/) 1.13.1 (WebJar) | UI icons | MIT |
| [Testcontainers](https://testcontainers.com/) | Integration tests against real MongoDB | MIT |
| [MongoDB](https://www.mongodb.com/) / [mongo-express](https://github.com/mongo-express/mongo-express) (Docker images) | Local dev stack | SSPL / MIT (respectively) |

## License

Apache License 2.0. See [LICENSE](LICENSE).
