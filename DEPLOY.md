# DEPLOY — Single-Port (443) Deployment Guide

> **General guideline** for deploying OmniDB Manager on any VPS with **only port `443` public**. Single `443` serves both Manager UI (HTTPS) and Postgres (TLS) via Nginx `stream` + `ssl_preread` (ALPN multiplex). No `5432` public. Adapt placeholders `<YOUR_DOMAIN>`, `<YOUR_VPS_IP>`, `<YOUR_EMAIL>` to your environment.

## Architecture

```
Internet:443 (<YOUR_DOMAIN>)
  → Nginx stream (ssl_preread on, port 443)
    ├─ ALPN h2 / http/1.1 → 127.0.0.1:8443 → Nginx http → 127.0.0.1:9811 (OmniDB Manager, Java 25)
    └─ ALPN empty (Postgres wire) → 127.0.0.1:5432 (Postgres, ssl=on, Let's Encrypt cert)

Internet:80 → Nginx http → 301 https://$host$request_uri (only for /.well-known/acme-challenge)
```

| Component | Listen | Public | Internal |
|---|---|---|---|
| Nginx stream | `0.0.0.0:443` | `<YOUR_DOMAIN>:443` | multiplex |
| Nginx http | `127.0.0.1:8443` ssl | — | your sites (e.g. `<YOUR_DOMAIN>`, other apps) |
| OmniDB Manager | `127.0.0.1:9811` | via `https://<YOUR_DOMAIN>/login` | `systemd omnidb.service` |
| Postgres | `127.0.0.1:5432` ssl | via `<YOUR_DOMAIN>:443` (stream) | `postgres` user |
| Certbot | — | `80` for `http-01` | `/etc/letsencrypt/live/<YOUR_DOMAIN>/` |

## 1. Prerequisites

- VPS (Ubuntu 24.04 recommended), user with `sudo`, SSH key
- Domain `<YOUR_DOMAIN>` with DNS `A` record → `<YOUR_VPS_IP>` (DNS only / grey cloud if using Cloudflare — Postgres `5432` cannot go via Cloudflare HTTP proxy)
- Only `443` + `80` (for Let's Encrypt) + `22` open. `5432` never public.
- If other sites already use `443` on the same VPS, they will be moved to `127.0.0.1:8443` to free `443` for `stream` (step 7).

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
ss -tlnp | grep -E "80|443|5432|9811"

# Install Nginx + stream module + Certbot + Postgres + Java 25
sudo apt update
sudo apt install -y nginx libnginx-mod-stream certbot python3-certbot-nginx postgresql-16 openjdk-25-jdk

nginx -V 2>&1 | tr ' ' '\n' | grep stream
# → --with-stream_ssl_module --with-stream_ssl_preread_module --with-stream=dynamic
java -version
# → openjdk 25.x
psql --version
# → 16.x
```

## 3. Postgres — Set Superuser Password

Ubuntu Postgres superuser is `postgres`, not `root`. Manager must use `postgres`.

Generate a strong password first (do not reuse):

```bash
openssl rand -base64 32
# → <POSTGRES_PASSWORD>  (save securely, e.g. password manager)
```

```bash
# Set postgres password to match .env (replace placeholder)
echo "ALTER USER postgres WITH PASSWORD '<POSTGRES_PASSWORD>';" | sudo -u postgres psql

# Verify local
PGPASSWORD='<POSTGRES_PASSWORD>' psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -c "select 1"
# → 1 row

# Postgres is ssl=on with snakeoil by default. Will switch to Let's Encrypt cert after cert is issued (step 5).
sudo cat /etc/postgresql/16/main/postgresql.conf | grep -E "^ssl|^listen|^port"
# ssl = on, ssl_cert_file = /etc/ssl/certs/ssl-cert-snakeoil.pem
```

## 4. Nginx — Prepare Port 80 for Let's Encrypt Challenge

```bash
# Create minimal http site for <YOUR_DOMAIN> on port 80 (only for /.well-known/acme-challenge)
sudo tee /etc/nginx/sites-available/<YOUR_DOMAIN> > /dev/null <<'NGINX80'
server {
    listen 80;
    server_name <YOUR_DOMAIN>;
    root /var/www/html;
    location /.well-known/acme-challenge/ { allow all; }
    location / { return 301 https://$host$request_uri; }
}
NGINX80
sudo ln -sf /etc/nginx/sites-available/<YOUR_DOMAIN> /etc/nginx/sites-enabled/<YOUR_DOMAIN>
sudo mkdir -p /var/www/html/.well-known/acme-challenge
sudo nginx -t && sudo systemctl reload nginx
ss -tlnp | grep -E "80|443"
# → 0.0.0.0:80, 0.0.0.0:443
```

## 5. Certbot — Get Certificate

Use `webroot` with port 80 (opened above). If your DNS provider token matches the zone, you can use `certonly --dns-cloudflare` instead.

```bash
sudo certbot certonly --webroot -w /var/www/html -d <YOUR_DOMAIN> --non-interactive --agree-tos -m <YOUR_EMAIL>

# Verify
sudo ls -lh /etc/letsencrypt/live/<YOUR_DOMAIN>/
# → fullchain.pem, privkey.pem
openssl x509 -in /etc/letsencrypt/live/<YOUR_DOMAIN>/fullchain.pem -noout -subject -dates
# → CN = <YOUR_DOMAIN>
```

## 6. Postgres — Switch to Let's Encrypt Certificate (Copy, Not Symlink)

Postgres user `postgres` (group `ssl-cert`) cannot read `/etc/letsencrypt/live` (root:root 750). Copy to a postgres-readable dir.

```bash
sudo mkdir -p /etc/postgresql/certs
sudo cp /etc/letsencrypt/live/<YOUR_DOMAIN>/fullchain.pem /etc/postgresql/certs/server.crt
sudo cp /etc/letsencrypt/live/<YOUR_DOMAIN>/privkey.pem /etc/postgresql/certs/server.key
sudo chown root:ssl-cert /etc/postgresql/certs/server.crt /etc/postgresql/certs/server.key
sudo chmod 640 /etc/postgresql/certs/server.crt /etc/postgresql/certs/server.key
sudo usermod -a -G ssl-cert postgres

# Update postgresql.conf
sudo sed -i "s|/etc/ssl/certs/ssl-cert-snakeoil.pem|/etc/postgresql/certs/server.crt|g" /etc/postgresql/16/main/postgresql.conf
sudo sed -i "s|/etc/ssl/private/ssl-cert-snakeoil.key|/etc/postgresql/certs/server.key|g" /etc/postgresql/16/main/postgresql.conf
grep -E "^ssl_cert|^ssl_key|^ssl =" /etc/postgresql/16/main/postgresql.conf
# → ssl = on, ssl_cert_file = '/etc/postgresql/certs/server.crt', ssl_key_file = '/etc/postgresql/certs/server.key'

sudo pg_ctlcluster 16 main restart
sleep 2
pg_lsclusters
# → 16 main 5432 online
sudo -u postgres psql -c "SHOW ssl; SHOW ssl_cert_file;"
# → on, /etc/postgresql/certs/server.crt
ss -tlnp | grep 5432
# → 127.0.0.1:5432

# Renewal hook (copies new cert on renew)
sudo mkdir -p /etc/letsencrypt/renewal-hooks/deploy
sudo tee /etc/letsencrypt/renewal-hooks/deploy/postgres-reload.sh > /dev/null <<'HOOK'
#!/bin/bash
cp /etc/letsencrypt/live/<YOUR_DOMAIN>/fullchain.pem /etc/postgresql/certs/server.crt
cp /etc/letsencrypt/live/<YOUR_DOMAIN>/privkey.pem /etc/postgresql/certs/server.key
chown root:ssl-cert /etc/postgresql/certs/server.crt /etc/postgresql/certs/server.key
chmod 640 /etc/postgresql/certs/server.crt /etc/postgresql/certs/server.key
pg_ctlcluster 16 main reload
HOOK
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/postgres-reload.sh
# Replace <YOUR_DOMAIN> in the hook with your actual domain
sudo sed -i "s|<YOUR_DOMAIN>|<YOUR_DOMAIN>|g" /etc/letsencrypt/renewal-hooks/deploy/postgres-reload.sh
```

## 7. Nginx — Stream Multiplex on 443 (Only Public Port)

Move all existing `443` http servers to `127.0.0.1:8443` (internal), let `stream` own public `443`.

```bash
# Move any existing sites that listen on 443 to 127.0.0.1:8443
# Example: sudo sed -i "s/listen 443 ssl http2;/listen 127.0.0.1:8443 ssl http2;/g" /etc/nginx/sites-enabled/<OTHER_SITE>

# Create stream.conf — ALPN multiplex
sudo tee /etc/nginx/stream.conf > /dev/null <<'STREAM'
stream {
    map $ssl_preread_alpn_protocols $upstream {
        ~\bh2\b 127.0.0.1:8443;
        ~\bhttp/1\.1\b 127.0.0.1:8443;
        default 127.0.0.1:5432;
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
}
NGINX
sudo ln -sf /etc/nginx/sites-available/<YOUR_DOMAIN> /etc/nginx/sites-enabled/<YOUR_DOMAIN>

# Fix any remaining 0.0.0.0:8443 → 127.0.0.1:8443
sudo sed -i "s/listen 0.0.0.0:8443/listen 127.0.0.1:8443/g" /etc/nginx/sites-enabled/*

sudo nginx -t && sudo systemctl reload nginx
# If "bind() to 127.0.0.1:8443 failed (98: Address already in use)" → systemctl stop nginx; fix; systemctl start nginx
ss -tlnp | grep -E "443|8443|5432|9811|80"
# → 0.0.0.0:80, 0.0.0.0:443 (stream), 127.0.0.1:8443 (http), 127.0.0.1:5432, 127.0.0.1:9811
```

Verify SNI:

```bash
echo | openssl s_client -connect 127.0.0.1:8443 -servername <YOUR_DOMAIN> 2>&1 | openssl x509 -noout -subject
# → CN = <YOUR_DOMAIN>
```

## 8. OmniDB Manager — Systemd Service

Jar `omnidb-manager-*.jar` is compiled with Java 25 (class file 69). Requires `openjdk-25-jdk`.

```bash
# Create .env at ~/omnidb/.env (Manager → DB local, issued strings public via 443)
# Generate secrets first:
#   openssl rand -base64 32  # for APP_ENCRYPTION_KEY
#   openssl rand -hex 16     # for passwords
cat > ~/omnidb/.env <<'ENV'
APP_ADMIN_USERNAME=<ADMIN_USER>
APP_ADMIN_PASSWORD=<ADMIN_PASSWORD>
SERVER_ADDRESS=127.0.0.1
RATE_LIMIT_TRUST_XFF=true
SERVER_COOKIE_SECURE=true
SERVER_COOKIE_SAME_SITE=lax
POSTGRES_ENABLED=true
POSTGRES_ROOT_USER=postgres
POSTGRES_ROOT_PASSWORD=<POSTGRES_PASSWORD>
POSTGRES_URI=jdbc:postgresql://127.0.0.1:5432/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10
POSTGRES_PUBLIC_HOST=<YOUR_DOMAIN>:443
POSTGRES_PUBLIC_TLS=false
POSTGRES_PUBLIC_SSLMODE=require
ADMINER_BASE_URL=http://127.0.0.1:9815
APP_ENCRYPTION_KEY=<BASE64_32_BYTES>
ENV
chmod 600 ~/omnidb/.env

# Systemd service
sudo tee /etc/systemd/system/omnidb.service > /dev/null <<'UNIT'
[Unit]
Description=OmniDB Manager
After=network.target postgresql.service
Wants=postgresql.service

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
# → Tomcat started on port 9811, PostgresConfig uri=jdbc:postgresql://127.0.0.1:5432/postgres?sslmode=disable..., username=postgres
ss -tlnp | grep 9811
# → [::ffff:127.0.0.1]:9811
```

## 9. Create a Database (Example)

```bash
# Via psql (or via Manager UI: https://<YOUR_DOMAIN>/login → Postgres → Create Database)
# Replace <DB_NAME>, <DB_USER>, <DB_PASSWORD> with your values
sudo -u postgres psql <<'SQL'
CREATE ROLE "<DB_USER>" WITH LOGIN PASSWORD '<DB_PASSWORD>';
CREATE DATABASE "<DB_NAME>" OWNER "<DB_USER>";
GRANT ALL PRIVILEGES ON DATABASE "<DB_NAME>" TO "<DB_USER>";
SQL
sudo -u postgres psql -d <DB_NAME> <<'SQL'
GRANT ALL ON SCHEMA public TO "<DB_USER>";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO "<DB_USER>";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO "<DB_USER>";
SQL

# Local test
PGPASSWORD='<DB_PASSWORD>' psql -h 127.0.0.1 -p 5432 -U <DB_USER> -d <DB_NAME> -c "select current_user, current_database(), now();"
# → <DB_USER> | <DB_NAME> | 1 row
```

## 10. Verification — All via 443

```bash
# Manager UI via 443 stream → 8443 → 9811
curl -k -s https://<YOUR_DOMAIN>/login | head -20
# → <!DOCTYPE html> ... Sign in · DB Manager

# Postgres via 443 stream → 5432 (ALPN empty)
PGPASSWORD='<DB_PASSWORD>' timeout 10 psql "host=<YOUR_DOMAIN> port=443 dbname=<DB_NAME> user=<DB_USER> sslmode=require" -c "select current_user, current_database(), now();"
# → <DB_USER> | <DB_NAME> | 1 row

PGPASSWORD='<POSTGRES_PASSWORD>' timeout 10 psql "host=<YOUR_DOMAIN> port=443 dbname=postgres user=postgres sslmode=require" -c "select 1"
# → 1 row

# Ports
ss -tlnp | grep -E "443|8443|5432|9811|80"
# → 0.0.0.0:80, 0.0.0.0:443 (stream), 127.0.0.1:8443, 127.0.0.1:5432, 127.0.0.1:9811
```

## 11. Docker Container Postgres (pgvector) with TLS

> **Alternative to sections 3/6/9.** Instead of the system `postgresql-16`, run Postgres as a Docker container (`pgvector/pgvector`) on `127.0.0.1:9813` — this is what the repo ships (`compose.postgres.yaml`) and what the pgvector extension requires. TLS uses a self-generated CA + server cert (not Let's Encrypt), and the nginx stream `default` route points at the container port, not the system `5432`.

### 11.1 Generate CA + server certs

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

### 11.2 Enable SSL in compose.postgres.yaml

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

### 11.3 Require SSL in pg_hba.conf

Change the catch-all TCP rule from `host` to `hostssl` so non-TLS connections are rejected:

```bash
sudo docker exec omnidb-postgres sed -i \
  's/^host all all all scram-sha-256$/hostssl all all all scram-sha-256/' \
  /var/lib/postgresql/18/docker/pg_hba.conf
```

### 11.4 Point nginx stream at the container

The stream `default` route must target the container port `9813`, not the system `5432`:

```nginx
stream {
    map $ssl_preread_alpn_protocols $upstream {
        ~\bh2\b 127.0.0.1:8443;
        ~\bhttp/1\.1\b 127.0.0.1:8443;
        default 127.0.0.1:9813;   # container postgres, not 5432
    }
    server {
        listen 443;
        ssl_preread on;
        proxy_pass $upstream;
        proxy_timeout 1h;
        proxy_connect_timeout 10s;
    }
}
```

### 11.5 Update .env

```bash
# App's own connection must now use TLS (container requires hostssl)
POSTGRES_URI=jdbc:postgresql://127.0.0.1:9813/postgres?user=postgres&password=<POSTGRES_PASSWORD>&sslmode=require&connectTimeout=5&socketTimeout=10
# Issued per-DB strings carry sslmode=require
POSTGRES_PUBLIC_TLS=true
POSTGRES_PUBLIC_SSLMODE=require
```

### 11.6 Recreate container + restart app

```bash
cd ~/omnidb
sudo docker compose -f compose.postgres.yaml up -d postgres   # recreates with SSL on, preserves data volume
sudo docker exec omnidb-postgres psql -U postgres -d postgres -tAc 'SHOW ssl;'   # → on
sudo systemctl restart omnidb
```

### 11.7 Verify

```bash
# SSL connection succeeds
PGPASSWORD='<DB_PASSWORD>' psql "postgresql://<DB_USER>:<DB_PASSWORD>@<YOUR_DOMAIN>:443/<DB_NAME>?sslmode=require&application_name=omnidb" \
  -c "SELECT ssl, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
# → t | TLS_AES_256_GCM_SHA384

# Non-SSL connection is rejected (proves SSL enforced)
PGPASSWORD='<DB_PASSWORD>' psql "postgresql://<DB_USER>:<DB_PASSWORD>@<YOUR_DOMAIN>:443/<DB_NAME>?sslmode=disable" -c "SELECT 1;"
# → FATAL: no pg_hba.conf entry ... no encryption
```

## Connection Strings

**Manager → Postgres (local, never public DNS):**
```
jdbc:postgresql://127.0.0.1:5432/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10
# user postgres, password from POSTGRES_ROOT_PASSWORD
```

**Issued per-DB string (what your apps use, via 443):**
```
postgresql://<DB_USER>:<DB_PASSWORD>@<YOUR_DOMAIN>:443/<DB_NAME>?sslmode=require
# Port is 443, not 5432 — only 443 is public. Add &application_name=omnidb if needed.
```

**Manager UI:**
```
https://<YOUR_DOMAIN>/login
# user <ADMIN_USER> / <ADMIN_PASSWORD> (from APP_ADMIN_USERNAME/PASSWORD)
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `psql: FATAL: password authentication failed for user "root"` | Ubuntu PG superuser is `postgres` | `POSTGRES_ROOT_USER=postgres` in `.env`, `ALTER USER postgres WITH PASSWORD` |
| `FATAL: could not load server certificate ... Permission denied` | `postgres` can't read `/etc/letsencrypt/live` | Copy to `/etc/postgresql/certs/server.crt/key` (`root:ssl-cert 640`), update `postgresql.conf` |
| `FATAL: private key file ... has group or world access` | Wrong perms | `chown root:ssl-cert`, `chmod 640` (not `postgres:postgres 640` with group access) |
| `bind() to 127.0.0.1:8443 failed (98: Address already in use)` | Old nginx still on `0.0.0.0:8443` | `systemctl stop nginx`, fix `listen` to `127.0.0.1:8443`, `systemctl start nginx` |
| `unknown directive "stream"` | `libnginx-mod-stream` not installed or `include` before `load_module` | `apt install libnginx-mod-stream`, ensure `include /etc/nginx/stream.conf;` is after `include /etc/nginx/modules-enabled/*.conf;` and before `http {` |
| `curl https://<YOUR_DOMAIN>` shows wrong cert | SNI mismatch, `8443` still `0.0.0.0:8443` | Ensure all `8443` are `127.0.0.1:8443`, `stream` owns `443` |
| `omnidb: UnsupportedClassVersionError class file version 69.0` | Jar needs Java 25, VPS has 21 | `apt install openjdk-25-jdk`, `update-alternatives --config java` |
| `omnidb: Unrecognized option: --sun-misc-unsafe-memory-access=allow` | Flag only on Java 25, service used Java 21 | Remove flag or use Java 25 |
| `<YOUR_DOMAIN>:5432` timeout | Only `443` is public, `5432` closed | Use `<YOUR_DOMAIN>:443` with `sslmode=require` |
| `FATAL: no pg_hba.conf entry ... no encryption` | SSL is enforced (`hostssl`), client connected without TLS | Use `sslmode=require` (or `verify-full`) in the connection string; never `sslmode=disable` |
| `Unable to determine zone_id for <YOUR_DOMAIN>` | Cloudflare token for wrong zone | Use `webroot` with port 80, or create token for correct zone |

## Renewal

```bash
# Cert auto-renews via systemd timer (certbot). Hook copies to postgres:
cat /etc/letsencrypt/renewal-hooks/deploy/postgres-reload.sh
# → cp fullchain.pem/privkey.pem → /etc/postgresql/certs/ → chown/chmod → pg_ctlcluster reload

# Test renew dry-run
sudo certbot renew --dry-run
```

## Files Changed on VPS

- `/etc/nginx/nginx.conf` — added `include /etc/nginx/stream.conf;` before `http {`
- `/etc/nginx/stream.conf` — new, ALPN multiplex `443` → `8443`/`5432` (or `9813` for the Docker container path, section 11)
- `/etc/nginx/sites-available/<YOUR_DOMAIN>` — new, `80` + `127.0.0.1:8443`
- Other sites' configs — `443` → `127.0.0.1:8443` (to free `443` for stream)
- `/etc/postgresql/16/main/postgresql.conf` — `ssl_cert_file/key_file` → `/etc/postgresql/certs/server.crt/key`
- `/etc/postgresql/certs/server.crt`, `server.key` — copied Let's Encrypt cert
- `/etc/systemd/system/omnidb.service` — new, `WorkingDirectory ~/omnidb`, `ExecStart java -jar omnidb-manager-*.jar`
- `~/omnidb/.env` — `POSTGRES_ROOT_USER=postgres`, `POSTGRES_PUBLIC_HOST=<YOUR_DOMAIN>:443`, `POSTGRES_URI=127.0.0.1:5432`
- `/etc/letsencrypt/live/<YOUR_DOMAIN>/` — new cert
- `iptables` — `INPUT` allow `80`, `443`

**Docker container path (section 11) additionally:**
- `~/omnidb/compose.postgres.yaml` — `ssl=on` + cert mounts on the `postgres` service
- `~/omnidb/certs/` — `ca.key`, `ca.crt`, `server.key`, `server.crt` (chowned to UID 999)
- Container `pg_hba.conf` (`/var/lib/postgresql/18/docker/pg_hba.conf`) — `host` → `hostssl` catch-all rule
- `~/omnidb/.env` — `POSTGRES_URI` → `127.0.0.1:9813` with `sslmode=require`, `POSTGRES_PUBLIC_TLS=true`
