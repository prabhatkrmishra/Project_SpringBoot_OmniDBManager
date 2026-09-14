## Operator recovery quick reference

All commands run on the deployment host. Tenant credentials below are
placeholders — substitute the issued values. Never print tenant passwords
into shared logs; never expose PostgreSQL/PgBouncer ports publicly.

## Architecture

- One public PostgreSQL endpoint: `db.example.com:15432` (host/port from
  `DATABASE_PROXY_HOST` / `DATABASE_PROXY_PORT`, loopback-bound by default
  via `DATABASE_PROXY_BIND`).
- Go TLS bridge terminates client TLS, reads `options=-c omnidb.mode=`
  (+ optional `-c omnidb.pool_profile=` for pooled) from the StartupMessage,
  then opens backend TLS (verify-full): `direct` → `postgres:5432`,
  `pooled` bare/`standard` → `pgbouncer:6432` → PostgreSQL,
  `pooled` + `high_concurrency` → `pgbouncer-hc:6433` → PostgreSQL.
- PostgreSQL is the final authentication/authorization authority (per-DB
  SCRAM roles, `REVOKE CONNECT FROM PUBLIC` on tenant databases).
- PgBouncer runs transaction pooling with `auth_query` against a
  least-privilege lookup function; MongoDB holds control-plane metadata
  (source of truth, incl. encrypted tenant passwords); MySQL is a second
  managed engine with server-global `user@'%'` accounts.
- Internal-only: PostgreSQL `5432`, PgBouncer `6432`, PgBouncer-HC `6433`,
  MongoDB `27017`, MySQL `3306`, admin UIs (Adminer/mongo-express/phpMyAdmin,
  loopback + ADMIN-gated in-app proxy). Externally reachable: the app UI/API
  (via nginx) and the single database gateway `:15432`.
- Pooled profiles: `standard` (existing pooler, default) and
  `high_concurrency` (dedicated pooler, transaction pooling with larger
  capped sizing). Profile selection is per connection; configuration is per
  pooler instance. Both use the same credentials/database; `5432`/`6432`/
  `6433` are never public.

## Normal verification

- Application liveness (anonymous, orchestrator-safe):
  `curl -fsS http://127.0.0.1:9811/actuator/health/liveness` → `{"status":"UP"}`.
- Application readiness (anonymous): `.../actuator/health/readiness`.
  UP means Spring serves management work; it never enumerates tenants.
  Dependency detail stays behind ADMIN-only `/actuator/health`.
- Proxy: `docker logs omnidb-database-proxy --tail 5` shows
  `route mode=direct|pooled profile=none|standard|high_concurrency backend=...`;
  no passwords/SCRAM/SQL/raw-options appear.
- PostgreSQL: `docker exec omnidb-postgres pg_isready` (also the container
  healthcheck).
- PgBouncer: stats login + `SHOW POOLS;` (also the container healthcheck).
- Direct (tenant creds, CA file): connect with
  `sslmode=verify-full` + `options="-c omnidb.mode=direct"`, expect the
  tenant identity from `SELECT current_user`.
- Pooled: same with `-c omnidb.mode=pooled` (bare = standard),
  `-c omnidb.mode=pooled -c omnidb.pool_profile=standard`, and
  `-c omnidb.mode=pooled -c omnidb.pool_profile=high_concurrency`;
  `wrong password` must fail on all modes/profiles. Unknown, duplicate, or
  direct+profile tokens fail closed (`missing or invalid connection mode`).
- Reconciliation (ADMIN): `GET /api/admin/reconcile` lists
  HEALTHY / MISSING_* / ORPHAN_* / INCONSISTENT per engine, read-only.

### Orphan PostgreSQL database (resource exists, metadata missing)

- Check: `SELECT datname FROM pg_database WHERE datname='<db>';`
  vs OmniDB database list.
- Remediate: if truly abandoned, as the Postgres superuser:
  `REVOKE CONNECT ON DATABASE "<db>" FROM PUBLIC;`
  terminate backends, `DROP DATABASE "<db>";`, `DROP ROLE "<role>";`.
- Verify: catalog queries no longer list them; provisioning the name again
  succeeds with a fresh identity.

### Metadata points at an absent resource

- Symptom: 409 on re-provision, or validation failures for a listed DB.
- Check: database/role existence in the engine catalogs.
- Remediate: restore the resource from backup, or delete the stale metadata
  record through OmniDB and re-provision (new identity is minted).

### PgBouncer stuck PAUSED (pooled logins hang after a crash)

- Check: pooler console `SHOW POOLS;` — a paused database shows clients waiting.
  Check BOTH instances: standard (`127.0.0.1:6432`) and HC (`pgbouncer-hc:6433`
  via the compose network).
- Remediate: restart OmniDB (startup RESUME reconciles every
  metadata-pooled database on BOTH poolers, standard then HC), or console
  `RESUME "<db>";` on each instance directly.
- Verify: new pooled logins succeed promptly on the public bridge on both profiles.

### Capacity budget (aggregate PostgreSQL backends)

- Usable tenant backend budget ~= `max_connections` (100) - control-plane (10)
  - operational reserve (5) ~= 85 server connections.
- Standard per-db worst case: `default_pool_size`(5) + `reserve_pool_size`(2);
  HC per-db worst case: `15 + 5`. With N pooled DBs on each profile, worst-case
  backends ~= N_std * 7 + N_hc * 20 (plus `max_db_connections` caps 10/25).
  Example: 10 pooled DBs x standard(5) + 2 HC DBs x HC(15) ~= 50 + 30 = 80 —
  fits; 10 x standard + 10 x HC ~= 50 + 150 = 200 EXCEEDS the budget.
- Operators must cap pooled DB count or lower per-db sizes before enabling HC
  broadly. HC bursts never resize standard pools (separate instances), but
  PostgreSQL itself remains the shared ceiling — document, do not overcommit.
- Bridge `max_conns`(2000) >> pooler `max_client_conn`(1000/instance) >>
  per-db server caps >> PG `max_connections`(100): backpressure narrows toward
  PostgreSQL, never the reverse.

### Rollback / mixed-version behavior

- NEW bridge: unknown profile is rejected (`missing or invalid connection
  mode`); no fallback to standard.
- A profiled HC string that reaches a pooler/backend that rejects unknown
  StartupMessage `options` (e.g. PgBouncer `ignore_startup_parameters` not
  covering `options`, or an OLD bridge that forwards `options=` unstripped)
  fails CLOSED with `unsupported startup parameter in options` — never silently
  downgraded. Live-verified: HEAD bridge forwards profiled strings to the
  standard pooler, which rejects them (see S-14 report).
- Therefore treat newly generated profiled strings as requiring the new bridge
  when HC semantics matter; bare pooled strings keep working everywhere.
- Delete-then-immediate-recreate of the same DB name can hit a pooler
  drop-window: server connections held by either pooler to the dropped DB
  crash on the first post-recreate `auth_query` (`server conn crashed?`),
  and the failed attempt's cleanup `DROP` can lose a race with pooler
  reconnects (`being accessed by other users`). Remediation: terminate
  backends for the name, `RECONNECT` on both poolers, retry once.
  Live-observed on both instances during S-14 verification; pre-existing
  single-pooler behavior, not profile-specific.

### PostgreSQL / PgBouncer / proxy unavailable

- Direct needs bridge + PostgreSQL; pooled additionally needs PgBouncer.
- Bridge refuses cleanly (`backend unavailable`); pooler denies unknown
  backends fail-closed. Restart order is irrelevant — each leg recovers
  independently (verified: new connections succeed seconds after restart).
- Existing TCP sessions die with the process they were attached to;
  clients must reconnect (no transparent survival is claimed).

### Expired / invalid proxy certificate

- The bridge keeps serving the last-good certificate and logs
  `public cert reload failed, keeping last good`; existing sessions are
  unaffected. Replace the files; the watcher picks them up (poll interval)
  and logs `public certificate reloaded`.
- With no usable certificate at all the bridge refuses to start (explicit
  log) — install a valid pair and restart.

### Encryption key unavailable / wrong

- Missing key with existing ciphertext, or a wrong key, fails explicitly
  (`Cannot decrypt` / `Decryption failed`) — never silent plaintext.
- Under the `atlas` profile the application refuses to start without a key.
- Key rotation is unsupported: changing the key makes old ciphertext
  unreadable. Keep the key backed up with the MongoDB metadata backup;
  restore both together, never one without the other.

### Rotated credential not connecting

- New pooled server sessions authenticate via `auth_query` against live
  `pg_authid`, so new logins take effect immediately; pre-existing pooled
  server connections drain on `RECONNECT` (issued automatically at
  rotation, warn-on-failure) or pooler restart.
- Remediate: re-rotate (converges — the superuser ALTER needs no old
  password), then verify direct + pooled logins with the new password.

### Partially completed restore

- Restore is per-table TRUNCATE + INSERT under one database lock and
  requires explicit confirmation; it is re-runnable to convergence.
- Remediate: fix the cause (see error), re-run the same backup file.

### Control-plane (OmniDB JVM) unavailable

- Established AND new tenant data-plane connections keep working: the
  bridge, PostgreSQL, and PgBouncer do not depend on the JVM after
  credentials/endpoints are issued. Rotation/provisioning/deletion wait
  for the control plane; tenant traffic does not.

## Critical safety warnings

- DO NOT expose PostgreSQL `:5432` or PgBouncer `:6432` publicly — the
  bridge `:15432` is the only application database ingress.
- DO NOT print tenant passwords, ciphertext, or connection strings with
  credentials into logs, tickets, or chat.
- DO NOT disable TLS or encryption to work around an incident.
- DO NOT automatically delete an orphan resource based solely on catalog
  mismatch — absence of metadata never proves ownership. Use
  `GET /api/admin/reconcile` to discover, then verify before acting.
- DO NOT manually rotate a credential (e.g. direct `ALTER ROLE`) without
  understanding control-plane state — metadata ciphertext will disagree
  with the database until the next managed rotation.
- DO NOT treat a metadata/resource mismatch as proof of anything except
  "needs investigation".

## Recovery semantics (established by live evidence)

- Provisioning crash-orphans are loud (409 on retry) and operator-recoverable.
- Rotation converges on retry (superuser ALTER needs no old password).
- Deletion is idempotent: DROP IF EXISTS → role → metadata converges on retry.
- Pooled PAUSE state is recovered on startup (best-effort RESUME of every
  metadata-pooled database).
- Partial restore is re-runnable to convergence (per-table TRUNCATE+INSERT).
- Control-plane outage does not terminate established tenant data-plane
  sessions, and new tenant connections keep working (verified live).
- Proxy restart necessarily terminates existing TCP sessions; new sessions
  recover immediately (no transparent survival is claimed).
- Encryption-key loss is unrecoverable without the original key (AES-GCM
  fails closed; legacy plaintext passes through only when it was stored
  that way).

## Backup / restore / upgrade

- Back up MongoDB metadata, per-tenant database dumps, the proxy CA/certs,
  and `APP_ENCRYPTION_KEY` together; test-restore the key + metadata pair
  first (a key mismatch fails explicitly, never silently).
- Uploads are bounded (256 MB compressed / 1 GB decompressed); oversized or
  malformed backups fail with 400, never OOM.
- Upgrade order is irrelevant across bridge/PostgreSQL/PgBouncer/app
  (verified restart matrix); downgrade with newer ciphertext under an old
  key is unsupported.
- Rollback limitation: metadata restore must pair with the matching
  resource state — restoring an older metadata backup while newer tenant
  resources exist surfaces as reconcile mismatches, not silent loss.

## Health model

- Liveness (`/actuator/health/liveness`, anonymous): JVM responsive only.
- Readiness (`/actuator/health/readiness`, anonymous): Spring ready to
  serve management work. Tenant/data-plane health is deliberately NOT an
  input — one sick tenant must not mark the control plane down.
- Full detail (`/actuator/health`, ADMIN-only): control-plane stores.
- The app runs as a host JVM (port 9811, loopback by default), not as a
  composed container, so there is intentionally no Docker HEALTHCHECK on
  the app itself — orchestrators poll the loopback liveness probe instead.
