# Query-audit collector (operator runbook)

The collector is VPS-local and lives inside the manager JVM (`AuditTailRunner`
+ `AuditCollectorService`). It tails database-native telemetry, normalizes and
redacts each record, and batch-writes canonical events to the `query_audit`
collection. The manager never sits in the tenant SQL path; the bridge never
parses SQL.

## Event flows

- PostgreSQL direct: client → bridge → PostgreSQL. Tails: PG `jsonlog` +
  bridge `bridge-session-start`. Correlation: EXACT via bridge SID
  (`application_name=omnidb:<sid>`, server-generated per connection).
  Attribution: AUTHORITATIVE/HIGH (exact ingress IP).
- PostgreSQL pooled standard/HC: client → bridge → PgBouncer(-HC) →
  PostgreSQL. Same tails. Correlation: EXACT via the same SID — both
  poolers run `track_extra_parameters = application_name` so each
  transaction checkout re-applies the owning client's value on the reused
  server connection (pinned image edoburu/pgbouncer v1.24.1-p1; re-verify
  on upgrade). Attribution: always INFERRED/MEDIUM per statement, but the
  IP itself is the exact originating ingress IP.
- Uncorrelated PG lines (missing/malformed/unknown SID, e.g. pre-SID
  clients, bridge restart windows): `sourceIp=null`, INFERRED/MEDIUM —
  never pooler/bridge/remote_host IP, never latest-session guess, never
  hostname, never AUTHORITATIVE.
- MySQL: client → MySQL. Tail: slow log (`long_query_time=0`, rotated).
  Attribution: AUTHORITATIVE/HIGH when the `User@Host` IP bracket holds an
  IP literal; hostname-only/local-socket lines become `sourceIp=null`,
  INFERRED (hostnames are never persisted as IP, never resolved).
  `root`/`mysql.sys` with a genuine tenant schema stays auditable (only
  system schemas or admin commands are control-plane surface).
- MongoDB: client → MongoDB. Tail: per-tenant `system.profile` (Community
  profiler, capped collection). Attribution: AUTHORITATIVE/HIGH when
  `client` parses to an IP literal (host:port split, IPv4/IPv6);
  otherwise null/INFERRED. `admin/local/config` never polled; a tenant
  user named `root`/`admin` inside a tenant database stays auditable.

## Enabling (all opt-in, disabled by default)

```bash
QUERY_AUDIT_ENABLED=true
QUERY_AUDIT_POSTGRES=true   # + PG jsonlog (below)
QUERY_AUDIT_MYSQL=true      # + MySQL slow log (below)
QUERY_AUDIT_MONGO=true      # + per-tenant profiler (below)
QUERY_AUDIT_RETENTION_DAYS=30
QUERY_AUDIT_MAX_SHAPE=2000
QUERY_AUDIT_PG_JSONLOG=/var/lib/postgresql/log/postgresql.json
QUERY_AUDIT_MYSQL_SLOWLOG=/var/lib/mysql/slow.log
QUERY_AUDIT_BRIDGE_LOG=/var/log/omnidb/bridge.log
```

PostgreSQL (bounded — prefer `mod` + `log_duration`; `all` is high-volume):

```
log_destination='jsonlog'
logging_collector=on
log_statement='mod'
log_duration=on
log_connections=on
```

MySQL (Community):

```
--slow-query-log=1 --long-query-time=0 --log-output=FILE
--slow-query-log-file=/var/lib/mysql/slow.log
```

MongoDB per tenant database (conservative sampling):

```
db.getSiblingDB('<tenant>').setProfilingLevel(1, {slowms: 50, sampleRate: 0.1})
```

## Operability

- Fail-open: Mongo outages/rotation/restarts never block tenant traffic.
  Drops surface on `/api/admin/query-activity/status` (`collector` +
  `tails`: queued, persisted, dropped, positions, last error).
- Resume: file tails persist inode+offset; profiler tails persist max `ts`
  per database. Guarantee is at-least-once + content/source dedupe —
  same-millisecond profiler siblings may replay once (absorbed downstream).
- Rotation: rename+new-file, truncate (copytruncate window observed),
  replacement all handled and covered by tests.
- Queue: 5000 events, batch 100, drop-oldest + counter, single bounded retry
  with jitter. Memory bounded: one capped line per file tail, 200 docs max
  per profiler poll per database, 10k dedupe keys + 10k sessions caps.
- Retention: TTL on `query_audit.observedAt` (1–365 days, default 30).
- Logs: tails log positions/errors only — never statement text, never secrets.
- Disable per engine: set the matching `QUERY_AUDIT_*` flag false and turn
  off the database-side telemetry; existing records are untouched.

## Rollback

1. Set `QUERY_AUDIT_ENABLED=false` (tails idle; UI shows empty state).
2. Revert database-side logging to previous values and reload.
3. Optionally drop `query_audit` — no tenant data depends on it.

## Deployment contract (CI/CD-ready)

- Artifact: the release JAR itself (no new binary, no new container image).
- Config: `QUERY_AUDIT_*` env only (see `.env.example`, `VARS.md`).
- Volumes (read-only into the manager host/process): PG log dir, MySQL slow
  log, bridge container log (see `compose.query-audit.yaml`).
- Permissions: read-only mounts; no DB credential changes; no pooler changes.
- Ordering: tails start 5s after boot when enabled; no dependency on tenant
  databases; Mongo outage degrades to drops, never crash-loop.
- Health: `/api/admin/query-activity/status` (ADMIN) — polls, positions,
  errors, lag, dropped.
- Production-disable: `QUERY_AUDIT_ENABLED=false` + remove the override file.
