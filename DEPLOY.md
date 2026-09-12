# DEPLOY — OmniDB Manager Deployment Guide

> **PRODUCTION (live since 2026-09-12, replaces the two-port design below for
> PostgreSQL):** single public hostname `db.missionhelmai.com`, single public
> port TCP `:14291`, pooled-only. `db.missionhelmai.com:14291` → Nginx stream
> (plain TCP passthrough, TLS untouched end-to-end) → PgBouncer `:6432`
> (client TLS required) → PostgreSQL `:5432`. Direct PostgreSQL has NO public
> route (loopback on-box, SSH tunnel from outside). Issued app strings look
> like `postgresql://user:pass@db.missionhelmai.com:14291/db?sslmode=require`.
> SNI routing was deliberately NOT used: stock PG clients open with cleartext
> SSLRequest before any TLS exists, so passive SNI inspection deadlocks them;
> with one backend there is nothing to select and SCRAM is the boundary (see
> `deploy/s06-cutover-gate.md` for the full live-verified checklist, and
> `deploy/database-proxy.stream.conf` for the why-not-SNI record).
> The `A`/`B` two-port sections below remain as the generic alternative, but
> they do NOT describe this deployment.


> **General guideline** for deploying OmniDB Manager on any VPS. Covers **all three engines** — MongoDB, PostgreSQL, MySQL — as Docker containers on loopback ports, with the Manager (Java 25) connecting via loopback `*_URI` and your apps dialing the **issued per-DB strings** via public DNS. Manager UI on `443` (HTTPS); Postgres on two non-standard public TCP ports `A` direct → `127.0.0.1:9813` (migrations/admin) and `B` pooled → `127.0.0.1:6432` (app/workers) via Nginx `stream` (TLS + IP allowlist, grey-cloud DNS). Adapt placeholders `<YOUR_DOMAIN>`, `<YOUR_VPS_IP>`, `<NON_STD_1>`/`<NON_STD_2>` (e.g. `27431`/`27432`) to your environment.

## Architecture

```
Internet:443 (<YOUR_DOMAIN>)
  → Nginx http (127.0.0.1:8443 ssl) → 127.0.0.1:9811 (OmniDB Manager, Java 25)
Internet:<NON_STD_1> (pg.example.com, grey-cloud, TLS, allowlist)
  → Nginx stream → 127.0.0.1:9813 (pgvector container, ssl=on) — direct A
Internet:<NON_STD_2> (pg.example.com, grey-cloud, TLS, allowlist)
  → Nginx stream → 127.0.0.1:6432 (PgBouncer, pool_mode=transaction) → 127.0.0.1:9813 — pooled B

Internet:80 → Nginx http → 301 https://$host$request_uri (only for /.well-known/acme-challenge)
```

All engines run as Docker containers, loopback-bound only. The Manager provisions them over loopback; your apps connect directly to the engine via the issued string (the Manager is the control plane, **not** a proxy).

| Component | Container / Process | Listen (loopback) | Public |
|---|---|---|---|
| OmniDB Manager | `omnidb.service` (Java 25) | `127.0.0.1:9811` | via `https://<YOUR_DOMAIN>/login` |
| MongoDB 8 | `omnidb-mongo` | `127.0.0.1:9812` | via `MONGODB_ISSUED_HOST` |
| mongo-express | `omnidb-mongo-express` | `127.0.0.1:9814` | via app proxy `/mongo-express` |
| PostgreSQL 18 (pgvector) | `omnidb-postgres` | `127.0.0.1:9813` | via `pg.example.com:<NON_STD_1>` (direct A, TLS, allowlist) |
| PgBouncer 1.24.1 | `omnidb-pgbouncer` | `127.0.0.1:6432` | via `pg.example.com:<NON_STD_2>` (pooled B, TLS, allowlist) |
| Adminer | `omnidb-adminer` | `127.0.0.1:9815` | via app proxy `/adminer` |
| MySQL 8.4 | `omnidb-mysql` | `127.0.0.1:9816` | via `MYSQL_ISSUED_HOST` |
| phpMyAdmin | `omnidb-phpmyadmin` | `127.0.0.1:9817` | via app proxy `/phpmyadmin` |
| Nginx stream | — | `0.0.0.0:<NON_STD_1>, 0.0.0.0:<NON_STD_2>` | `pg.example.com:<NON_STD_1>/<NON_STD_2>` |
| Nginx http | — | `127.0.0.1:8443` ssl | your sites |

> **Two Postgres links:** `A` direct (`<NON_STD_1>` → `127.0.0.1:9813`) for DDL/migrations/break-glass (tighter IP allowlist); `B` pooled (`<NON_STD_2>` → `127.0.0.1:6432` → `127.0.0.1:9813`) for app/workers. Both require TLS (`sslmode=require`/`verify-full`) + per-DB `SCRAM-SHA-256` + IP allowlist. Non-standard port is camouflage only — never a substitute for the allowlist. DNS must be grey-cloud (DNS only) so TCP reaches your VPS, not Cloudflare HTTP proxy. MongoDB/MySQL use their own public ports/streams (see §5.2/§5.3).

## 1. Prerequisites

- VPS (Ubuntu 24.04 recommended), user with `sudo`, SSH key
- Domain `<YOUR_DOMAIN>` + `pg.example.com` with DNS `A` records → `<YOUR_VPS_IP>` (DNS only / grey cloud if using Cloudflare — raw DB TCP cannot go via Cloudflare HTTP proxy, must be grey-cloud)
- Only `443` + `80` (for Let's Encrypt) + `22` open by default. DB loopback ports (`9812`, `9813`, `9816`, `6432`) never public. Postgres public ports `<NON_STD_1>` (e.g. `27431` direct) + `<NON_STD_2>` (e.g. `27432` pooled) are opened **only** to your app servers via IP allowlist (never `0.0.0.0/0`) — see §6 and `deploy/nginx.conf.example`.
- If other sites already use `443` on the same VPS, they will be moved to `127.0.0.1:8443` so `443` stays for the Manager UI (step 6).

Verify DNS:

```bash
dig <YOUR_DOMAIN> +short
# → <YOUR_VPS_IP>
```

## 2. VPS — Base Packages

```bash
ssh -i ~/.ssh/<YOUR_KEY> <YOUR_USER>@<YOUR_VPS_IP>

# Open firewall for Let's Encrypt http-01 (80) + https (443)
sudo iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 1 -p tcp --dport 443 -j ACCEPT
sudo iptables -L INPUT -n --line-numbers | head -10
ss -tlnp | grep -E "80|443|9811|9812|9813|9816"

# Install Nginx + stream module + Certbot + Docker + Java 25
sudo apt update
sudo apt install -y nginx libnginx-mod-stream certbot python3-certbot-nginx docker.io docker-compose-v2 openjdk-25-jdk
sudo systemctl enable --now docker
sudo usermod -aG docker <YOUR_USER>

nginx -V 2>&1 | tr ' ' '\n' | grep stream
# → --with-stream_ssl_module --with-stream_ssl_preread_module --with-stream=dynamic
java -version
# → openjdk 25.x
docker --version
```

## 3. Docker Containers — All Engines

The repo ships an orchestrator plus one compose file per engine. Copy `compose*.yaml` and `.env` to `~/omnidb/`.

```bash
mkdir -p ~/omnidb && cd ~/omnidb
# copy compose.yaml, compose.mongo.yaml, compose.postgres.yaml, compose.mysql.yaml, .env here

# All engines:
docker compose up -d
# Or per-engine (only what you enable):
docker compose -f compose.mongo.yaml up -d
docker compose -f compose.postgres.yaml up -d
docker compose -f compose.mysql.yaml up -d
```

| Engine | Image | Container | Loopback port | Admin UI |
|---|---|---|---|---|
| MongoDB | `mongo:8` | `omnidb-mongo` | `127.0.0.1:9812` | mongo-express `127.0.0.1:9814` |
| PostgreSQL | `pgvector/pgvector:0.8.6-pg18-trixie` | `omnidb-postgres` | `127.0.0.1:9813` | Adminer `127.0.0.1:9815` |
| MySQL | `mysql:8.4` | `omnidb-mysql` | `127.0.0.1:9816` | phpMyAdmin `127.0.0.1:9817` |

Verify all healthy:

```bash
docker ps --format '{{.Names}} {{.Status}}'
# → omnidb-mongo Up (healthy), omnidb-postgres Up (healthy), omnidb-mysql Up (healthy), ...
```

## 4. Environment (.env)

Copy `.env.example` → `.env` and fill real values. **Key rule:** `*_URI` is the **Manager → DB root link** on `127.0.0.1` (never public DNS); `*_ISSUED_HOST` (+ `*_ISSUED_PORT` for Postgres) is what your **apps** dial in the issued strings.

```bash
cp .env.example .env
# generate secrets:
#   openssl rand -base64 32   # APP_ENCRYPTION_KEY
#   openssl rand -hex 16      # each DB root password
chmod 600 ~/omnidb/.env
```

Minimal production `.env` (all three engines enabled):

```env
# === App admin login ===
APP_ADMIN_USERNAME=<ADMIN_USER>
APP_ADMIN_PASSWORD=<ADMIN_PASSWORD>

# === Network / HTTPS (behind Nginx / Cloudflare Tunnel) ===
SERVER_ADDRESS=127.0.0.1
RATE_LIMIT_TRUST_XFF=true
SERVER_COOKIE_SECURE=true
SERVER_COOKIE_SAME_SITE=lax

# === MongoDB engine ===
MONGO_ENABLED=true
MONGODB_ROOT_PASSWORD=<MONGO_ROOT_PASSWORD>
MONGODB_ISSUED_HOST=mongo.example.com
# OVERRIDE_MONGODB_TLS=true  # if stream TLS on (see §5.2)

# === PostgreSQL engine ===
POSTGRES_ENABLED=true
POSTGRES_ROOT_PASSWORD=<POSTGRES_ROOT_PASSWORD>
POSTGRES_ISSUED_HOST=pg.example.com          # DNS only, no :port
POSTGRES_ISSUED_PORT=27431                   # public direct A -> 127.0.0.1:9813 (migrations/admin)
PGBOUNCER_ISSUED_PORT=27432                  # public pooled B -> 127.0.0.1:6432 (app/workers)
PGBOUNCER_ADMIN_PASSWORD=<PGBOUNCER_ADMIN_PASSWORD>
PGBOUNCER_STATS_PASSWORD=<PGBOUNCER_STATS_PASSWORD>
# OVERRIDE_POSTGRES_SSLMODE=require  # or verify-full with CA (see §10)

# === MySQL engine ===
MYSQL_ENABLED=true
MYSQL_ROOT_PASSWORD=<MYSQL_ROOT_PASSWORD>
MYSQL_ISSUED_HOST=mysql.example.com
# OVERRIDE_MYSQL_TLS=false  # if stream TLS on (see §5.3)

# === Encryption at rest (AES-256-GCM) ===
APP_ENCRYPTION_KEY=<BASE64_32_BYTES>
```

> **Manager → DB vs issued strings:** `*_URI` stays `127.0.0.1` (Manager and DB on the same host via Docker). `*_ISSUED_HOST`/`*_ISSUED_PORT` is what your apps dial. Never give the root `*_URI` to your apps. Postgres uses two public ports: `POSTGRES_ISSUED_PORT` (direct `A` → `127.0.0.1:9813`) and `PGBOUNCER_ISSUED_PORT` (pooled `B` → `127.0.0.1:6432`). See `VARS.md` for the full variable reference.

## 5. TLS per Engine

### 5.1 PostgreSQL — two links (direct A + pooled B, both TLS)

Postgres runs with `ssl=on` (self-signed CA or Let's Encrypt) and `hostssl` in `pg_hba.conf`. Two public TCP ports expose it: `A` direct (`<NON_STD_1>` e.g. `27431` → `127.0.0.1:9813`) for DDL/migrations/break-glass and `B` pooled (`<NON_STD_2>` e.g. `27432` → `127.0.0.1:6432` → `127.0.0.1:9813`) for app/workers. Both streams terminate TLS at Nginx (`listen <NON_STD_*> ssl`) with the same cert, and both require IP allowlist + `sslmode=require`/`verify-full` + per-DB `SCRAM-SHA-256`. Full steps in §10 and `deploy/nginx.conf.example`. Issued strings carry `sslmode=require` (or `verify-full` with CA) and the port from `POSTGRES_ISSUED_PORT` / `PGBOUNCER_ISSUED_PORT`. Non-standard port is camouflage only — the allowlist + TLS + per-DB credentials are the real locks. DNS must be grey-cloud (DNS only).

### 5.2 MongoDB

MongoDB native TLS requires `mongod --tlsMode requireTLS` + certs (not configured in `compose.mongo.yaml` by default). The simplest public-TLS option is Nginx `stream` TLS termination on a dedicated public port:

```nginx
stream {
    server {
        listen 27017 ssl;
        ssl_certificate     /etc/letsencrypt/live/<YOUR_DOMAIN>/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/<YOUR_DOMAIN>/privkey.pem;
        proxy_pass 127.0.0.1:9812;
    }
}
```

Then in `.env`:

```env
MONGODB_ISSUED_HOST=mongo.example.com
OVERRIDE_MONGODB_TLS=true        # issued strings get &tls=true
```

### 5.3 MySQL

MySQL 8.4 enables TLS by default with auto-generated certs. For a trusted CA, either configure MySQL's own certs or terminate TLS at Nginx `stream` on a dedicated public port:

```nginx
stream {
    server {
        listen 3306 ssl;
        ssl_certificate     /etc/letsencrypt/live/<YOUR_DOMAIN>/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/<YOUR_DOMAIN>/privkey.pem;
        proxy_pass 127.0.0.1:9816;
    }
}
```

Then in `.env`:

```env
MYSQL_ISSUED_HOST=mysql.example.com
OVERRIDE_MYSQL_TLS=true        # issued strings get ?sslMode=REQUIRED (or VERIFY_IDENTITY with CA)
```

## 6. Nginx — Manager UI on 443 + Postgres on two non-standard ports

Manager UI stays on `443` via `127.0.0.1:8443` (plain `http` reverse proxy, no `stream` multiplex). Postgres is exposed on **two** separate public TCP ports via `stream` — `A` direct (`<NON_STD_1>` e.g. `27431` → `127.0.0.1:9813`) for DDL/migrations/break-glass and `B` pooled (`<NON_STD_2>` e.g. `27432` → `127.0.0.1:6432` → `127.0.0.1:9813`) for app/workers. Both streams terminate TLS and are IP-allowlisted (never `0.0.0.0/0`); DNS must be grey-cloud (DNS only) so TCP reaches your VPS.

> **Future: single-port SNI proxy.** The planned end-state serves both links on one public port `:15432` (`db.*` → Postgres, `pool.*` → PgBouncer, unknown SNI rejected). Do **not** cut over yet — see `deploy/s06-cutover-gate.md` (19 checks: proxy health, SNI routing, TLS, isolation, failure semantics) and `deploy/database-proxy.stream.conf`. Until every box passes, this two-port section stays authoritative.

```bash
# Move any existing sites that listen on 443 to 127.0.0.1:8443
# Example: sudo sed -i "s/listen 443 ssl http2;/listen 127.0.0.1:8443 ssl http2;/g" /etc/nginx/sites-enabled/<OTHER_SITE>

# One-time: enable stream module and include dir
sudo apt install -y libnginx-mod-stream
sudo mkdir -p /etc/nginx/streams-enabled
# Add to the TOP LEVEL of /etc/nginx/nginx.conf (after the http {} block):
#   stream {
#       include /etc/nginx/streams-enabled/*.conf;
#   }
# Ensure it is after `include /etc/nginx/modules-enabled/*.conf;` and before `http {`.

# Create Postgres streams — two non-standard ports, each TLS + allowlist
sudo tee /etc/nginx/streams-enabled/postgres.conf > /dev/null <<'STREAM'
# Direct A — migrations/admin (tighter allowlist)
server {
    listen 27431 ssl;
    ssl_certificate     /etc/letsencrypt/live/pg.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/pg.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers on;
    proxy_pass 127.0.0.1:9813;
    proxy_timeout 1h;
    proxy_connect_timeout 10s;
    # Optional per-IP guardrail
    # limit_conn pg_direct_per_ip 20;
}
# Pooled B — app/workers
server {
    listen 27432 ssl;
    ssl_certificate     /etc/letsencrypt/live/pg.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/pg.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers on;
    proxy_pass 127.0.0.1:6432;
    proxy_timeout 1h;
    proxy_connect_timeout 10s;
    # limit_conn pg_pooled_per_ip 100;
}
STREAM

# Create/update site for <YOUR_DOMAIN> (80 + 127.0.0.1:8443) — Manager UI only
sudo tee /etc/nginx/sites-available/<YOUR_DOMAIN> > /dev/null <<'NGINX'
server {
    listen 80;
    server_name <YOUR_DOMAIN>;
    root /var/www/html;
    location /.well-known/acme-challenge/ { allow all; }
    # Actuator never on public name either (http just redirects, but block explicitly)
    location ^~ /actuator/ { return 404; }
    location = /actuator { return 404; }
    location / { return 301 https://$host$request_uri; }
}
server {
    listen 127.0.0.1:8443 ssl http2;
    server_name <YOUR_DOMAIN>;
    ssl_certificate /etc/letsencrypt/live/<YOUR_DOMAIN>/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/<YOUR_DOMAIN>/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers on;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    client_max_body_size 256m;                 # restore uploads
    location ^~ /actuator/ { return 404; }   # manager-only: never on a public name
    location = /actuator { return 404; }
    location / {
        proxy_pass http://127.0.0.1:9811;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 90s;
    }
    location /monitor/stream {                 # SSE — no buffering
        proxy_pass http://127.0.0.1:9811;
        proxy_buffering off;
        proxy_read_timeout 3600s;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX
sudo ln -sf /etc/nginx/sites-available/<YOUR_DOMAIN> /etc/nginx/sites-enabled/<YOUR_DOMAIN>

# Fix any remaining 0.0.0.0:8443 → 127.0.0.1:8443
sudo sed -i "s/listen 0.0.0.0:8443/listen 127.0.0.1:8443/g" /etc/nginx/sites-enabled/*

sudo nginx -t && sudo systemctl reload nginx
# If "bind() to 127.0.0.1:8443 failed (98: Address already in use)" → systemctl stop nginx; fix; systemctl start nginx
ss -tlnp | grep -E "443|8443|9811|9812|9813|9816|27431|27432|6432"
# → 0.0.0.0:80, 0.0.0.0:27431 (stream direct), 0.0.0.0:27432 (stream pooled), 127.0.0.1:8443 (http), 127.0.0.1:9811..9817 + 127.0.0.1:6432 (containers)
```

> **Note:** `/actuator/*` is blocked at the proxy above (`location ^~ /actuator/` + `location = /actuator` → 404) on both `80` and `127.0.0.1:8443`, so it is never reachable via the public name. On the box itself it still requires the manager login (form login, `ADMIN` role) — open `http://127.0.0.1:9811/actuator/health` in a logged-in browser, or `curl` with the `JSESSIONID` cookie from a prior `POST /login`. Defense in depth: the app itself requires `hasRole("ADMIN")` for actuator, so even a direct hit on the app port never answers anonymously. Postgres streams above are **not** HTTP — they are raw TCP with TLS; actuator blocking does not apply there, but they are protected by TLS + IP allowlist + per-DB `SCRAM-SHA-256`.

Verify SNI (Manager UI):

```bash
echo | openssl s_client -connect 127.0.0.1:8443 -servername <YOUR_DOMAIN> 2>&1 | openssl x509 -noout -subject
# → CN = <YOUR_DOMAIN>
# Postgres direct/pooled (replace with your real ports):
echo | openssl s_client -connect pg.example.com:27431 -servername pg.example.com 2>&1 | openssl x509 -noout -subject
# → CN = pg.example.com
echo | openssl s_client -connect pg.example.com:27432 -servername pg.example.com 2>&1 | openssl x509 -noout -subject
# → CN = pg.example.com
```

## 7. OmniDB Manager — Systemd Service

Jar `omnidb-manager-*.jar` is compiled with Java 25 (class file 69). Requires `openjdk-25-jdk`.

```bash
sudo tee /etc/systemd/system/omnidb.service > /dev/null <<'UNIT'
[Unit]
Description=OmniDB Manager
After=network.target docker.service
Wants=docker.service

[Service]
User=<YOUR_LINUX_USER>
WorkingDirectory=/home/<YOUR_LINUX_USER>/omnidb
ExecStart=/bin/bash -c 'set -a; source /home/<YOUR_LINUX_USER>/omnidb/.env; exec /usr/bin/java -Xms256m -Xmx512m -jar /home/<YOUR_LINUX_USER>/omnidb/omnidb-manager-*.jar'
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable omnidb
sudo systemctl restart omnidb
sleep 15
sudo systemctl status omnidb | head -20
# → active (running), Main PID java
sudo journalctl -u omnidb --no-pager | tail -20
# → Tomcat started on port 9811, PostgresConfig uri=jdbc:postgresql://127.0.0.1:9813/postgres?...sslmode=require...
ss -tlnp | grep 9811
# → [::ffff:127.0.0.1]:9811
```

## 8. Provision a Database (per engine)

Sign in at `https://<YOUR_DOMAIN>/login`, pick an engine, then **Provision a database**. The Manager runs the DDL over loopback and returns an issued connection string. Equivalent CLI:

```bash
# MongoDB
docker exec omnidb-mongo mongosh "mongodb://<MONGO_ROOT>:<PASS>@127.0.0.1:27017/admin" \
  --eval 'db.getSiblingDB("<DB_NAME>").createUser({user:"<DB_USER>",pwd:"<DB_PASSWORD>",roles:[{role:"readWrite",db:"<DB_NAME>"}]})'

# PostgreSQL
docker exec omnidb-postgres psql -U postgres -d postgres <<'SQL'
CREATE ROLE "<DB_USER>" WITH LOGIN PASSWORD '<DB_PASSWORD>';
CREATE DATABASE "<DB_NAME>" OWNER "<DB_USER>" TEMPLATE template0 ENCODING 'UTF8';
GRANT CONNECT ON DATABASE "<DB_NAME>" TO "<DB_USER>";
SQL

# MySQL
docker exec omnidb-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' <<'SQL'
CREATE DATABASE `<DB_NAME>` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '<DB_USER>'@'%' IDENTIFIED BY '<DB_PASSWORD>';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP ON `<DB_NAME>`.* TO '<DB_USER>'@'%';
SQL
```

## 9. Verification

```bash
# Manager UI via 127.0.0.1:8443 → 9811
curl -k -s https://<YOUR_DOMAIN>/login | head -20
# → <!DOCTYPE html> ... Sign in · DB Manager

# Postgres direct A (migrations/admin) via <NON_STD_1> → 9813
PGPASSWORD='<DB_PASSWORD>' timeout 10 psql "host=pg.example.com port=27431 dbname=<DB_NAME> user=<DB_USER> sslmode=require" -c "select current_user, current_database(), now();"
# → <DB_USER> | <DB_NAME> | 1 row

# Postgres pooled B (app/workers) via <NON_STD_2> → 6432 → 9813
PGPASSWORD='<DB_PASSWORD>' timeout 10 psql "host=pg.example.com port=27432 dbname=<DB_NAME> user=<DB_USER> sslmode=require" -c "select current_user;"
# → <DB_USER> (1 row)

# Ports
ss -tlnp | grep -E "443|8443|9811|9812|9813|9816|27431|27432|6432|80"
# → 0.0.0.0:80, 0.0.0.0:27431 (stream direct), 0.0.0.0:27432 (stream pooled), 127.0.0.1:8443, 127.0.0.1:9811..9817 + 127.0.0.1:6432
```

## Connection Strings

**Manager → DB (root, loopback, never public DNS):**
```
MONGODB_URI=mongodb://root:<PASS>@127.0.0.1:9812/?authSource=admin&maxPoolSize=10
POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?user=postgres&password=<PASS>&sslmode=require&connectTimeout=5&socketTimeout=10
MYSQL_URI=jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000
```

**Issued per-DB strings (what your apps use, via public DNS):**
```
# MongoDB
mongodb://<DB_USER>:<DB_PASSWORD>@mongo.example.com/<DB_NAME>?authSource=<DB_NAME>        # + &tls=true if OVERRIDE_MONGODB_TLS=true

# PostgreSQL direct A (migrations/admin) — POSTGRES_ISSUED_PORT (e.g. 27431)
postgresql://<DB_USER>:<DB_PASSWORD>@pg.example.com:27431/<DB_NAME>?sslmode=require&application_name=omnidb
# verify-full: postgresql://<DB_USER>:<DB_PASSWORD>@pg.example.com:27431/<DB_NAME>?sslmode=verify-full&sslrootcert=/path/to/ca.crt&application_name=omnidb

# PostgreSQL pooled B (app/workers) — PGBOUNCER_ISSUED_PORT (e.g. 27432), only for DBs with Route via PgBouncer
postgresql://<DB_USER>:<DB_PASSWORD>@pg.example.com:27432/<DB_NAME>?sslmode=require&application_name=omnidb

# MySQL
mysql://<DB_USER>:<DB_PASSWORD>@mysql.example.com:3306/<DB_NAME>?sslMode=REQUIRED
# JDBC: jdbc:mysql://mysql.example.com:3306/<DB_NAME>?sslMode=REQUIRED&serverTimezone=UTC
```

**Manager UI:**
```
https://<YOUR_DOMAIN>/login
# user <ADMIN_USER> / <ADMIN_PASSWORD> (from APP_ADMIN_USERNAME/PASSWORD)
```

## Custom Public DB Ports

Postgres **always** uses two non-standard public TCP ports (see §6): `<NON_STD_1>` (e.g. `27431`) direct `A` → `127.0.0.1:9813` and `<NON_STD_2>` (e.g. `27432`) pooled `B` → `127.0.0.1:6432`. MongoDB and MySQL optionally use a single custom port each. Each engine gets one public port (Postgres gets two, one per link); every database on that engine shares it (databases are distinguished by dbname + credentials, not by port). Enabling a database in OmniDB opens nothing by itself — traffic flows only once the stream servers (§6) and the firewall rules below both exist. Non-standard port is camouflage only — the allowlist + TLS + per-DB credentials are the real locks.

| Engine | Stream target (loopback) | Public port | Env vars | Notes |
|---|---|---|---|---|
| MongoDB | `127.0.0.1:9812` | `<CUSTOM_PORT>` | `MONGODB_ISSUED_HOST=mongo.example.com:<CUSTOM_PORT>` | `OVERRIDE_MONGODB_TLS=true` for `&tls=true` |
| PostgreSQL direct `A` | `127.0.0.1:9813` | `<NON_STD_1>` e.g. `27431` | `POSTGRES_ISSUED_HOST=pg.example.com` + `POSTGRES_ISSUED_PORT=<NON_STD_1>` | `?sslmode=require` (or `verify-full` with CA), tighter allowlist |
| PostgreSQL pooled `B` | `127.0.0.1:6432` | `<NON_STD_2>` e.g. `27432` | same `POSTGRES_ISSUED_HOST` + `PGBOUNCER_ISSUED_PORT=<NON_STD_2>` | Only for DBs provisioned with **Route via PgBouncer** |
| MySQL | `127.0.0.1:9816` | `<CUSTOM_PORT>` | `MYSQL_ISSUED_HOST=mysql.example.com:<CUSTOM_PORT>` | `OVERRIDE_MYSQL_TLS=true` for `?sslMode=REQUIRED` |

Use a **different** `<CUSTOM_PORT>` per engine — one port cannot serve two engines without SNI routing. Pooled and direct Postgres must also differ from each other (`<NON_STD_1>` ≠ `<NON_STD_2>`).

### NSG / security-group rules (one per public DB port)

Default-deny everything inbound; allow each custom port **only** from your app servers (never `0.0.0.0/0`). Host-level `ufw` stays as a second layer (see `deploy/nginx.conf.example`). Each `<NON_STD_*>` allow only the MissionHelm IP (or your app server IP), grey-cloud DNS, never `0.0.0.0/0`.

```bash
# Azure NSG — Postgres direct A (tighter allowlist, e.g. only MissionHelm IP):
az network nsg rule create \
  --resource-group <RESOURCE_GROUP> --nsg-name <NSG_NAME> \
  --name allow-pg-direct --priority 110 \
  --source-address-prefixes <MISSION_HELM_IP>/32 \
  --destination-port-ranges <NON_STD_1> \
  --destination-address-prefixes '*' \
  --access Allow --protocol Tcp --direction Inbound

# Azure NSG — Postgres pooled B (app/workers):
az network nsg rule create \
  --resource-group <RESOURCE_GROUP> --nsg-name <NSG_NAME> \
  --name allow-pg-pooled --priority 111 \
  --source-address-prefixes <APP_SERVER_IP>/32 \
  --destination-port-ranges <NON_STD_2> \
  --destination-address-prefixes '*' \
  --access Allow --protocol Tcp --direction Inbound

# AWS security group equivalents:
aws ec2 authorize-security-group-ingress \
  --group-id <SECURITY_GROUP_ID> \
  --protocol tcp --port <NON_STD_1> --cidr <MISSION_HELM_IP>/32
aws ec2 authorize-security-group-ingress \
  --group-id <SECURITY_GROUP_ID> \
  --protocol tcp --port <NON_STD_2> --cidr <APP_SERVER_IP>/32
```

Portal path is the same rule: inbound, TCP, port `<NON_STD_*>`, source `<APP_SERVER_IP>/32` (or `<MISSION_HELM_IP>/32` for direct), allow. Keep `80`/`443`/`22` as §2 already has them; `9811..9817` and `6432` stay loopback-only (no cloud rule at all). `pgvector` stays inside the same Postgres — no extra port.

### How enabled databases pick up the ports

1. Add the two stream servers (§6) and reload nginx (`sudo nginx -t && sudo systemctl reload nginx`).
2. Add the NSG rules above and set `POSTGRES_ISSUED_HOST` (DNS only) + `POSTGRES_ISSUED_PORT=<NON_STD_1>` + `PGBOUNCER_ISSUED_PORT=<NON_STD_2>` in `~/omnidb/.env`, then restart the jar. For Mongo/MySQL, set `*_ISSUED_HOST` to `host:<CUSTOM_PORT>`.
3. Provision (or open the detail page of) a database — the issued string now carries the custom port. Strings are snapshots: apps holding an older string keep dialing the old port until they adopt the new one, so change ports only when ready to rotate client configs (password reset reissues).

Verify (replace placeholders; Postgres shown, others analogous):

```bash
echo | openssl s_client -connect pg.example.com:<NON_STD_1> -servername pg.example.com 2>&1 | openssl x509 -noout -subject
# → CN = pg.example.com (proves TLS terminates on your stream, direct A)

echo | openssl s_client -connect pg.example.com:<NON_STD_2> -servername pg.example.com 2>&1 | openssl x509 -noout -subject
# → CN = pg.example.com (pooled B)

PGPASSWORD='<DB_PASSWORD>' timeout 10 psql "host=pg.example.com port=<NON_STD_1> dbname=<DB_NAME> user=<DB_USER> sslmode=require" -c "select current_user;"
# → <DB_USER> (1 row, direct)

PGPASSWORD='<DB_PASSWORD>' timeout 10 psql "host=pg.example.com port=<NON_STD_2> dbname=<DB_NAME> user=<DB_USER> sslmode=require" -c "select current_user;"
# → <DB_USER> (1 row, pooled — only for DBs with Route via PgBouncer)
```

## 10. Docker Container Postgres (pgvector) with TLS

> The repo ships Postgres as a Docker container (`pgvector/pgvector`) on `127.0.0.1:9813` — this is what the pgvector extension requires. TLS uses a self-generated CA + server cert (not Let's Encrypt), and the nginx stream `default` route points at the container port, not the system `5432`.

### 10.1 Generate CA + server certs

```bash
cd ~/omnidb
mkdir -p certs && cd certs

# CA
openssl genrsa -out ca.key 2048
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
  -subj "/CN=OmniDB PostgreSQL CA" -out ca.crt

# Server key + CSR (SAN covers both public names + localhost).
# The Manager UI (<YOUR_DOMAIN>) and the two Postgres streams
# (pg.example.com) terminate TLS separately — either issue one cert with
# both DNS names as SANs, or separate certs per stream block. A single-name
# cert on the wrong name gives TLS name mismatch on the other link.
openssl genrsa -out server.key 2048
chmod 600 server.key
openssl req -new -key server.key -subj "/CN=pg.example.com" -out server.csr

cat > san.cnf <<'EOF'
[req]
distinguished_name = dn
req_extensions = v3_req
[dn]
[v3_req]
subjectAltName = @alt_names
[alt_names]
DNS.1 = pg.example.com
DNS.2 = <YOUR_DOMAIN>
DNS.3 = localhost
IP.1 = 127.0.0.1
EOF

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 3650 -sha256 -extfile san.cnf -extensions v3_req

# Container runs as UID 999 (postgres) and is read-only — chown so it can read the certs
sudo chown -R 999:999 ~/omnidb/certs
sudo chmod 600 ~/omnidb/certs/server.key
```

### 10.2 Enable SSL in compose.postgres.yaml

The `postgres` service must set `ssl=on` and mount the certs read-only:

```yaml
    command: ["postgres",
      "-c", "password_encryption=scram-sha-256",
      "-c", "ssl=on",
      "-c", "ssl_cert_file=/var/lib/postgresql/server.crt",
      "-c", "ssl_key_file=/var/lib/postgresql/server.key",
      "-c", "ssl_ca_file=/var/lib/postgresql/ca.crt"]
    volumes:
      - postgres-data:/var/lib/postgresql
      - ./certs/server.crt:/var/lib/postgresql/server.crt:ro
      - ./certs/server.key:/var/lib/postgresql/server.key:ro
      - ./certs/ca.crt:/var/lib/postgresql/ca.crt:ro
```

### 10.3 Require SSL in pg_hba.conf

Change the catch-all TCP rule from `host` to `hostssl` so non-TLS connections are rejected:

```bash
sudo docker exec omnidb-postgres sed -i \
  's/^host all all all scram-sha-256$/hostssl all all all scram-sha-256/' \
  /var/lib/postgresql/18/docker/pg_hba.conf
```

### 10.4 Point nginx streams at the containers

Two `stream` servers expose Postgres (see §6): direct `A` (`<NON_STD_1>` e.g. `27431` → `127.0.0.1:9813`) and pooled `B` (`<NON_STD_2>` e.g. `27432` → `127.0.0.1:6432` → `127.0.0.1:9813`). Both terminate TLS at Nginx and are IP-allowlisted. Neither is the system `5432`.

### 10.5 Update .env

```bash
# App's own connection must now use TLS (container requires hostssl).
# Keep the URI credential-free: PostgresConfig logs in as root with
# POSTGRES_ROOT_PASSWORD separately — embedding user=/password= here
# duplicates the credential and the two can drift apart silently.
OVERRIDE_POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=require&connectTimeout=5&socketTimeout=10
POSTGRES_ROOT_PASSWORD=<POSTGRES_ROOT_PASSWORD>
# Issued per-DB strings carry sslmode=require + the two public ports
POSTGRES_ISSUED_HOST=pg.example.com          # DNS only, no :port
POSTGRES_ISSUED_PORT=27431                   # direct A -> 127.0.0.1:9813
PGBOUNCER_ISSUED_PORT=27432                  # pooled B -> 127.0.0.1:6432
OVERRIDE_POSTGRES_SSLMODE=require            # or verify-full with CA
# Pooler auth_user credential (must match the running pooler container's
# userlist — pooled logins fail if these disagree; see §10.8)
PGBOUNCER_AUTH_PASSWORD=<PGBOUNCER_AUTH_PASSWORD>
```

### 10.6 Recreate container + restart app

```bash
cd ~/omnidb
sudo docker compose -f compose.postgres.yaml up -d postgres   # recreates with SSL on, preserves data volume
sudo docker exec omnidb-postgres psql -U postgres -d postgres -tAc 'SHOW ssl;'   # → on
sudo systemctl restart omnidb
```

### 10.7 Verify

```bash
# SSL connection succeeds — direct A (migrations/admin)
PGPASSWORD='<DB_PASSWORD>' psql "postgresql://<DB_USER>:<DB_PASSWORD>@pg.example.com:27431/<DB_NAME>?sslmode=require&application_name=omnidb" \
  -c "SELECT ssl, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
# → t | TLS_AES_256_GCM_SHA384

# Pooled B (app/workers) — only for DBs with Route via PgBouncer
PGPASSWORD='<DB_PASSWORD>' psql "postgresql://<DB_USER>:<DB_PASSWORD>@pg.example.com:27432/<DB_NAME>?sslmode=require&application_name=omnidb" \
  -c "SELECT ssl, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
# → t | TLS_AES_256_GCM_SHA384

# Non-SSL connection is rejected (proves SSL enforced)
PGPASSWORD='<DB_PASSWORD>' psql "postgresql://<DB_USER>:<DB_PASSWORD>@pg.example.com:27431/<DB_NAME>?sslmode=disable" -c "SELECT 1;"
# → FATAL: no pg_hba.conf entry ... no encryption
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `psql: FATAL: password authentication failed for user "root"` | Postgres superuser is `postgres` | `POSTGRES_ROOT_USER=postgres` in `.env`, `ALTER USER postgres WITH PASSWORD` |
| `FATAL: no pg_hba.conf entry ... no encryption` | SSL is enforced (`hostssl`), client connected without TLS | Use `sslmode=require` (or `verify-full`); never `sslmode=disable` |
| `FATAL: private key file ... has group or world access` | Wrong perms on cert key | `chown 999:999`, `chmod 600` on `~/omnidb/certs/server.key` |
| `bind() to 127.0.0.1:8443 failed (98: Address already in use)` | Old nginx still on `0.0.0.0:8443` | `systemctl stop nginx`, fix `listen` to `127.0.0.1:8443`, `systemctl start nginx` |
| `unknown directive "stream"` | `libnginx-mod-stream` not installed or bad include placement | `apt install libnginx-mod-stream`, ensure the `stream { include /etc/nginx/streams-enabled/*.conf; }` block is top-level after `include /etc/nginx/modules-enabled/*.conf;` and before `http {` |
| `curl https://<YOUR_DOMAIN>` shows wrong cert | SNI mismatch between the Manager cert and the Postgres stream certs | Use one SAN cert covering both names, or separate certs per stream block (see §10.1) |
| `omnidb: UnsupportedClassVersionError class file version 69.0` | Jar needs Java 25, VPS has 21 | `apt install openjdk-25-jdk`, `update-alternatives --config java` |
| `pg.example.com:<NON_STD_2>` pooled login `password authentication failed` | Pooler `auth_user` credential mismatch, or lookup function missing on that DB | Check `PGBOUNCER_AUTH_PASSWORD` matches the running pooler container, then use the detail page **Install pooled auth** button (see §10.8) |
| `FATAL: auth_query error` in pooler logs | `pgbouncer.user_lookup(text)` missing on the target DB | Same fix — per-DB repair via the detail page; verify with `isPooledAuthInstalled` probe |
| `MongoTimeoutError` / `ECONNREFUSED` on issued Mongo string | `MONGODB_ISSUED_HOST` wrong or port not exposed | Set `MONGODB_ISSUED_HOST` + expose Mongo via Nginx `stream` (§5.2) |
| `Public Key Retrieval is not allowed` (MySQL) | Missing `allowPublicKeyRetrieval=true` | Add `&allowPublicKeyRetrieval=true` to the JDBC URI |
| `Unable to determine zone_id for <YOUR_DOMAIN>` | Cloudflare token for wrong zone | Use `webroot` with port 80, or create token for correct zone |

## Renewal

```bash
# Let's Encrypt cert auto-renews via systemd timer (certbot).
# Postgres uses its own self-generated CA cert (10-year) — rotate before expiry.
sudo certbot renew --dry-run
```

## Files Changed on VPS

- `/etc/nginx/nginx.conf` — top-level `stream { include /etc/nginx/streams-enabled/*.conf; }` after modules, before `http {`
- `/etc/nginx/streams-enabled/postgres.conf` — new, two TLS stream servers: `<NON_STD_1>` → `127.0.0.1:9813` (direct A), `<NON_STD_2>` → `127.0.0.1:6432` (pooled B)
- `/etc/nginx/sites-available/<YOUR_DOMAIN>` — new, `80` + `127.0.0.1:8443` (Manager UI only)
- Other sites' configs — `443` → `127.0.0.1:8443` (443 stays with the Manager UI; Postgres uses the two non-standard ports)
- `/etc/systemd/system/omnidb.service` — new, `WorkingDirectory ~/omnidb`, `ExecStart java -jar omnidb-manager-*.jar`
- `~/omnidb/.env` — all engine root URIs + public hosts + TLS flags
- `~/omnidb/compose*.yaml` — engine containers (mongo/postgres/mysql + admin UIs)
- `~/omnidb/certs/` — `ca.key`, `ca.crt`, `server.key`, `server.crt` (Postgres TLS, chowned to UID 999)
- Container `pg_hba.conf` (`/var/lib/postgresql/18/docker/pg_hba.conf`) — `host` → `hostssl` catch-all rule
- `iptables` — `INPUT` allow `80`, `443`, plus `<NON_STD_1>`/`<NON_STD_2>` allowlisted to app-server IPs only

### 10.8 Pooled-auth repair (existing pooled DBs)

Databases provisioned pooled before the `auth_query` path existed have the
pooled flag but no `pgbouncer.user_lookup(text)` function, so pooled logins
fail. The detail page shows **pooled auth missing** for these and offers
**Install pooled auth** (admin only, idempotent — skips when already
installed). New provisions install it automatically. Password rotation needs
no pooler reload: `auth_query` reads the live SCRAM verifier from
`pg_authid`, so a reset takes effect on the next pooled login.
