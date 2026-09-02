# DEPLOY — OmniDB Manager Deployment Guide

> **General guideline** for deploying OmniDB Manager on any VPS. Covers **all three engines** — MongoDB, PostgreSQL, MySQL — as Docker containers on loopback ports, with the Manager (Java 25) connecting via loopback `*_URI` and your apps dialing the **issued per-DB strings** via public DNS. A single public port `443` multiplexes the Manager UI (HTTPS) and Postgres (TLS) through Nginx `stream` + `ssl_preread` (ALPN). Adapt placeholders `<YOUR_DOMAIN>`, `<YOUR_VPS_IP>` to your environment.

## Architecture

```
Internet:443 (<YOUR_DOMAIN>)
  → Nginx stream (ssl_preread on, port 443)
    ├─ ALPN h2 / http/1.1 → 127.0.0.1:8443 → Nginx http → 127.0.0.1:9811 (OmniDB Manager, Java 25)
    └─ ALPN empty (Postgres wire) → 127.0.0.1:9813 (pgvector container, ssl=on)

Internet:80 → Nginx http → 301 https://$host$request_uri (only for /.well-known/acme-challenge)
```

All engines run as Docker containers, loopback-bound only. The Manager provisions them over loopback; your apps connect directly to the engine via the issued string (the Manager is the control plane, **not** a proxy).

| Component | Container / Process | Listen (loopback) | Public |
|---|---|---|---|
| OmniDB Manager | `omnidb.service` (Java 25) | `127.0.0.1:9811` | via `https://<YOUR_DOMAIN>/login` |
| MongoDB 8 | `omnidb-mongo` | `127.0.0.1:9812` | via `MONGODB_PUBLIC_HOST` |
| mongo-express | `omnidb-mongo-express` | `127.0.0.1:9814` | via app proxy `/mongo-express` |
| PostgreSQL 18 (pgvector) | `omnidb-postgres` | `127.0.0.1:9813` | via `<YOUR_DOMAIN>:443` (stream) |
| Adminer | `omnidb-adminer` | `127.0.0.1:9815` | via app proxy `/adminer` |
| MySQL 8.4 | `omnidb-mysql` | `127.0.0.1:9816` | via `MYSQL_PUBLIC_HOST` |
| phpMyAdmin | `omnidb-phpmyadmin` | `127.0.0.1:9817` | via app proxy `/phpmyadmin` |
| Nginx stream | — | `0.0.0.0:443` | `<YOUR_DOMAIN>:443` |
| Nginx http | — | `127.0.0.1:8443` ssl | your sites |

> **Single-port 443** cleanly multiplexes the Manager UI (HTTP ALPN) and Postgres (TLS, empty ALPN). MongoDB and MySQL use their own wire protocols and are exposed via their own public ports/streams (see §6.2/§6.3) — they do not ride the same 443 ALPN multiplex as Postgres.

## 1. Prerequisites

- VPS (Ubuntu 24.04 recommended), user with `sudo`, SSH key
- Domain `<YOUR_DOMAIN>` with DNS `A` record → `<YOUR_VPS_IP>` (DNS only / grey cloud if using Cloudflare — raw DB ports cannot go via Cloudflare HTTP proxy)
- Only `443` + `80` (for Let's Encrypt) + `22` open. DB ports (`5432`, `27017`, `3306`) never public.
- If other sites already use `443` on the same VPS, they will be moved to `127.0.0.1:8443` to free `443` for `stream` (step 6).

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

Copy `.env.example` → `.env` and fill real values. **Key rule:** `*_URI` is the **Manager → DB root link** on `127.0.0.1` (never public DNS); `*_PUBLIC_HOST` is what your **apps** dial in the issued strings.

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
MONGODB_ROOT_USERNAME=root
MONGODB_ROOT_PASSWORD=<MONGO_ROOT_PASSWORD>
MONGODB_URI=mongodb://root:<MONGO_ROOT_PASSWORD>@127.0.0.1:9812/?authSource=admin&maxPoolSize=10
MONGO_EXPRESS_USERNAME=admin
MONGO_EXPRESS_PASSWORD=<MONGO_EXPRESS_PASSWORD>
MONGODB_PUBLIC_HOST=mongo.example.com
MONGODB_PUBLIC_TLS=false

# === PostgreSQL engine ===
POSTGRES_ENABLED=true
POSTGRES_ROOT_USER=postgres
POSTGRES_ROOT_PASSWORD=<POSTGRES_ROOT_PASSWORD>
POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?user=postgres&password=<POSTGRES_ROOT_PASSWORD>&sslmode=require&connectTimeout=5&socketTimeout=10
POSTGRES_PUBLIC_HOST=<YOUR_DOMAIN>:443
POSTGRES_PUBLIC_TLS=true
POSTGRES_PUBLIC_SSLMODE=require

# === MySQL engine ===
MYSQL_ENABLED=true
MYSQL_ROOT_PASSWORD=<MYSQL_ROOT_PASSWORD>
MYSQL_URI=jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000
MYSQL_PUBLIC_HOST=mysql.example.com
MYSQL_PUBLIC_TLS=false
MYSQL_PUBLIC_SSLMODE=REQUIRED

# === Encryption at rest (AES-256-GCM) ===
APP_ENCRYPTION_KEY=<BASE64_32_BYTES>
```

> **Manager → DB vs issued strings:** `*_URI` stays `127.0.0.1` (Manager and DB on the same host via Docker). `*_PUBLIC_HOST` is what your apps dial. Never give the root `*_URI` to your apps. See `VARS.md` for the full variable reference.

## 5. TLS per Engine

### 5.1 PostgreSQL (container certs, single 443)

Generate a CA + server cert, enable `ssl=on` in `compose.postgres.yaml`, and require `hostssl` in `pg_hba.conf`. Full steps in §10 below (or the `postgres` service comments in `compose.postgres.yaml`). Issued strings carry `sslmode=require` (or `verify-full` with the CA).

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
MONGODB_PUBLIC_HOST=mongo.example.com
MONGODB_PUBLIC_TLS=true        # issued strings get &tls=true
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
MYSQL_PUBLIC_HOST=mysql.example.com
MYSQL_PUBLIC_SSLMODE=REQUIRED        # or VERIFY_IDENTITY with a CA truststore
```

## 6. Nginx — Stream Multiplex on 443 (Only Public Port)

Move all existing `443` http servers to `127.0.0.1:8443` (internal), let `stream` own public `443`.

```bash
# Move any existing sites that listen on 443 to 127.0.0.1:8443
# Example: sudo sed -i "s/listen 443 ssl http2;/listen 127.0.0.1:8443 ssl http2;/g" /etc/nginx/sites-enabled/<OTHER_SITE>

# Create stream.conf — ALPN multiplex (app + Postgres on 443)
sudo tee /etc/nginx/stream.conf > /dev/null <<'STREAM'
stream {
    map $ssl_preread_alpn_protocols $upstream {
        ~\bh2\b 127.0.0.1:8443;
        ~\bhttp/1\.1\b 127.0.0.1:8443;
        default 127.0.0.1:9813;   # Postgres container (not system 5432)
    }
    server {
        listen 443;
        ssl_preread on;
        proxy_pass $upstream;
        proxy_timeout 1h;
        proxy_connect_timeout 10s;
    }
}
STREAM

# Include stream before http (after modules)
sudo sed -i "/include \/etc\/nginx\/stream.conf;/d" /etc/nginx/nginx.conf
sudo sed -i "/^http {/i include /etc/nginx/stream.conf;" /etc/nginx/nginx.conf
head -15 /etc/nginx/nginx.conf
# → include /etc/nginx/modules-enabled/*.conf; ... include /etc/nginx/stream.conf; http {

# Create/update site for <YOUR_DOMAIN> (80 + 127.0.0.1:8443)
sudo tee /etc/nginx/sites-available/<YOUR_DOMAIN> > /dev/null <<'NGINX'
server {
    listen 80;
    server_name <YOUR_DOMAIN>;
    root /var/www/html;
    location /.well-known/acme-challenge/ { allow all; }
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
ss -tlnp | grep -E "443|8443|9811|9812|9813|9816"
# → 0.0.0.0:80, 0.0.0.0:443 (stream), 127.0.0.1:8443 (http), 127.0.0.1:9811..9817 (containers)
```

Verify SNI:

```bash
echo | openssl s_client -connect 127.0.0.1:8443 -servername <YOUR_DOMAIN> 2>&1 | openssl x509 -noout -subject
# → CN = <YOUR_DOMAIN>
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

## 9. Verification — All via 443

```bash
# Manager UI via 443 stream → 8443 → 9811
curl -k -s https://<YOUR_DOMAIN>/login | head -20
# → <!DOCTYPE html> ... Sign in · DB Manager

# Postgres via 443 stream → 9813 (ALPN empty, TLS)
PGPASSWORD='<DB_PASSWORD>' timeout 10 psql "host=<YOUR_DOMAIN> port=443 dbname=<DB_NAME> user=<DB_USER> sslmode=require" -c "select current_user, current_database(), now();"
# → <DB_USER> | <DB_NAME> | 1 row

# Ports
ss -tlnp | grep -E "443|8443|9811|9812|9813|9816|80"
# → 0.0.0.0:80, 0.0.0.0:443 (stream), 127.0.0.1:8443, 127.0.0.1:9811..9817
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
mongodb://<DB_USER>:<DB_PASSWORD>@mongo.example.com/<DB_NAME>?authSource=<DB_NAME>        # + &tls=true if MONGODB_PUBLIC_TLS=true

# PostgreSQL (via 443)
postgresql://<DB_USER>:<DB_PASSWORD>@<YOUR_DOMAIN>:443/<DB_NAME>?sslmode=require&application_name=omnidb
# verify-full: postgresql://<DB_USER>:<DB_PASSWORD>@<YOUR_DOMAIN>:443/<DB_NAME>?sslmode=verify-full&sslrootcert=/path/to/ca.crt&application_name=omnidb

# MySQL
mysql://<DB_USER>:<DB_PASSWORD>@mysql.example.com:3306/<DB_NAME>?sslMode=REQUIRED
# JDBC: jdbc:mysql://mysql.example.com:3306/<DB_NAME>?sslMode=REQUIRED&serverTimezone=UTC
```

**Manager UI:**
```
https://<YOUR_DOMAIN>/login
# user <ADMIN_USER> / <ADMIN_PASSWORD> (from APP_ADMIN_USERNAME/PASSWORD)
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

# Server key + CSR (SAN covers the public host + localhost)
openssl genrsa -out server.key 2048
chmod 600 server.key
openssl req -new -key server.key -subj "/CN=<YOUR_DOMAIN>" -out server.csr

cat > san.cnf <<'EOF'
[req]
distinguished_name = dn
req_extensions = v3_req
[dn]
[v3_req]
subjectAltName = @alt_names
[alt_names]
DNS.1 = <YOUR_DOMAIN>
DNS.2 = localhost
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

### 10.4 Point nginx stream at the container

The stream `default` route must target the container port `9813`, not the system `5432` (see §6).

### 10.5 Update .env

```bash
# App's own connection must now use TLS (container requires hostssl)
POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?user=postgres&password=<POSTGRES_PASSWORD>&sslmode=require&connectTimeout=5&socketTimeout=10
# Issued per-DB strings carry sslmode=require
POSTGRES_PUBLIC_TLS=true
POSTGRES_PUBLIC_SSLMODE=require
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
# SSL connection succeeds
PGPASSWORD='<DB_PASSWORD>' psql "postgresql://<DB_USER>:<DB_PASSWORD>@<YOUR_DOMAIN>:443/<DB_NAME>?sslmode=require&application_name=omnidb" \
  -c "SELECT ssl, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
# → t | TLS_AES_256_GCM_SHA384

# Non-SSL connection is rejected (proves SSL enforced)
PGPASSWORD='<DB_PASSWORD>' psql "postgresql://<DB_USER>:<DB_PASSWORD>@<YOUR_DOMAIN>:443/<DB_NAME>?sslmode=disable" -c "SELECT 1;"
# → FATAL: no pg_hba.conf entry ... no encryption
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `psql: FATAL: password authentication failed for user "root"` | Postgres superuser is `postgres` | `POSTGRES_ROOT_USER=postgres` in `.env`, `ALTER USER postgres WITH PASSWORD` |
| `FATAL: no pg_hba.conf entry ... no encryption` | SSL is enforced (`hostssl`), client connected without TLS | Use `sslmode=require` (or `verify-full`); never `sslmode=disable` |
| `FATAL: private key file ... has group or world access` | Wrong perms on cert key | `chown 999:999`, `chmod 600` on `~/omnidb/certs/server.key` |
| `bind() to 127.0.0.1:8443 failed (98: Address already in use)` | Old nginx still on `0.0.0.0:8443` | `systemctl stop nginx`, fix `listen` to `127.0.0.1:8443`, `systemctl start nginx` |
| `unknown directive "stream"` | `libnginx-mod-stream` not installed or `include` before `load_module` | `apt install libnginx-mod-stream`, ensure `include /etc/nginx/stream.conf;` is after `include /etc/nginx/modules-enabled/*.conf;` and before `http {` |
| `curl https://<YOUR_DOMAIN>` shows wrong cert | SNI mismatch, `8443` still `0.0.0.0:8443` | Ensure all `8443` are `127.0.0.1:8443`, `stream` owns `443` |
| `omnidb: UnsupportedClassVersionError class file version 69.0` | Jar needs Java 25, VPS has 21 | `apt install openjdk-25-jdk`, `update-alternatives --config java` |
| `<YOUR_DOMAIN>:5432` timeout | Only `443` is public, `5432` closed | Use `<YOUR_DOMAIN>:443` with `sslmode=require` |
| `MongoTimeoutError` / `ECONNREFUSED` on issued Mongo string | `MONGODB_PUBLIC_HOST` wrong or port not exposed | Set `MONGODB_PUBLIC_HOST` + expose Mongo via Nginx `stream` (§5.2) |
| `Public Key Retrieval is not allowed` (MySQL) | Missing `allowPublicKeyRetrieval=true` | Add `&allowPublicKeyRetrieval=true` to the JDBC URI |
| `Unable to determine zone_id for <YOUR_DOMAIN>` | Cloudflare token for wrong zone | Use `webroot` with port 80, or create token for correct zone |

## Renewal

```bash
# Let's Encrypt cert auto-renews via systemd timer (certbot).
# Postgres uses its own self-generated CA cert (10-year) — rotate before expiry.
sudo certbot renew --dry-run
```

## Files Changed on VPS

- `/etc/nginx/nginx.conf` — added `include /etc/nginx/stream.conf;` before `http {`
- `/etc/nginx/stream.conf` — new, ALPN multiplex `443` → `8443`/`9813` (app + Postgres container)
- `/etc/nginx/sites-available/<YOUR_DOMAIN>` — new, `80` + `127.0.0.1:8443`
- Other sites' configs — `443` → `127.0.0.1:8443` (to free `443` for stream)
- `/etc/systemd/system/omnidb.service` — new, `WorkingDirectory ~/omnidb`, `ExecStart java -jar omnidb-manager-*.jar`
- `~/omnidb/.env` — all engine root URIs + public hosts + TLS flags
- `~/omnidb/compose*.yaml` — engine containers (mongo/postgres/mysql + admin UIs)
- `~/omnidb/certs/` — `ca.key`, `ca.crt`, `server.key`, `server.crt` (Postgres TLS, chowned to UID 999)
- Container `pg_hba.conf` (`/var/lib/postgresql/18/docker/pg_hba.conf`) — `host` → `hostssl` catch-all rule
- `iptables` — `INPUT` allow `80`, `443`
