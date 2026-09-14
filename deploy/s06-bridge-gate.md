# Cutover Gate — single-host TLS-bridge Database Proxy (:15432)

Deployment:

```text
INTERNET :15432 ──► bridge (db.example.com, TLS endpoint)
  ├── options=-c omnidb.mode=direct ──TLS──► postgres:5432
  └── options=-c omnidb.mode=pooled  ──TLS──► pgbouncer:6432 ──TLS──► postgres:5432
```

One public hostname + one public port serve BOTH modes. The proxy terminates
client TLS, requires SNI == the public host, reads StartupMessage `options`,
extracts exactly one `omnidb.mode`, strips only that token, then relays
blindly. PostgreSQL stays the auth authority. No passwords/SQL logged.

Do NOT hand out bridged strings until every box below passes with live
evidence. The bridge stays disabled until then
(`DATABASE_PROXY_ENABLED=false` default, `PublicEndpointConsistencyGuard`
rejects a missing `database.proxy.host` at startup).

## Preconditions

- [ ] Prod `DATABASE_PROXY_HOST` decided (e.g. `db.example.com`).
- [ ] Public cert for the host installed (`./certs/server.crt|key`).
- [ ] Internal CA installed (`./certs/ca.crt`); backend certs for
      `postgres` and `pgbouncer` SANs chain to it.
- [ ] SG/NSG: expose ONLY TCP `:15432`; `5432`/`6432`/`9813`/`9815` denied
      externally (verified, not assumed).

## Routing proof

- [ ] `options=-c omnidb.mode=direct → PostgreSQL` (packet/log evidence).
- [ ] `options=-c omnidb.mode=pooled → PgBouncer` (packet/log evidence).
- [ ] Missing mode → rejected, no backend contact.
- [ ] Invalid mode → rejected, no backend contact.
- [ ] Duplicate/conflicting mode → rejected, no backend contact.
- [ ] Unknown/missing SNI → rejected pre-startup.
- [ ] Cleartext StartupMessage → rejected.
- [ ] Oversized startup (>16 KiB) → rejected.
- [ ] Routing token stripped: `omnidb.mode` never reaches PG/PgBouncer;
      other `options` tokens + params survive unchanged.

## TLS proof

- [ ] Client TLS validates against the public cert/SAN.
- [ ] Bridge→PG uses verify-full against the internal CA (`postgres` SAN).
- [ ] Bridge→pooler uses verify-full (`pgbouncer` SAN).
- [ ] Bad backend CA → rejected, no plaintext fallback.
- [ ] Cert reload failure keeps the last good context (no plaintext).

## Auth proof

- [ ] Same DB/user/password work in both modes.
- [ ] Wrong password rejected in both modes.
- [ ] Proxy never authenticates users itself; no credentials logged/persisted.

## Client matrix (4 × 2)

- [ ] psql/libpq DIRECT + POOLED (StartupMessage capture shows the mode).
- [ ] pgJDBC DIRECT + POOLED (`options` property verified on the wire).
- [ ] pg8000 DIRECT + POOLED or explicitly CONDITIONAL with reason.
- [ ] Node pg DIRECT + POOLED or explicitly CONDITIONAL with reason.
- [ ] Each: correct backend, isolation (A cannot touch B), TLS verify,
      transactions, prepared statements, cancel, reconnect, timeout sane.

## Channel binding

Bridge terminates client TLS, so `SCRAM-SHA-256-PLUS` bound to the
client-facing certificate can never verify against the bridge→backend leg
(proven live: DIRECT advertises `PLUS`, POOLED offers plain SCRAM only).
Issued bridged strings therefore pin `channel_binding=disable`
(URI) / `channelBinding=disable` (JDBC) for plain `SCRAM-SHA-256`.

- [ ] `channel_binding=disable` DIRECT + POOLED pass (issued contract).
- [ ] `channel_binding=prefer` DIRECT fails with
      `SCRAM channel binding check failed`, POOLED passes (documented
      limitation — PLUS cannot survive the bridge; clients must use the
      issued `disable` strings).
- [ ] `channel_binding=require` fails CLEANLY both modes (documented
      bridge limitation — no end-to-end binding through a TLS endpoint;
      DIRECT fails on binding mismatch, POOLED fails with no PLUS offered).
- [ ] pg8000 DIRECT explicitly UNSUPPORTED (v1.31.5 forces
      `tls-server-end-point` binding whenever TLS is on, no opt-out —
      proven live); pg8000 POOLED passes. Endpoints/docs must not claim
      pg8000 DIRECT compatibility.

## Cancellation

- [ ] CancelRequest reaches the exact owning backend for concurrent
      DIRECT + POOLED sessions (BackendKeyData pinning, no broadcast).
- [ ] Unknown cancel keys fail closed without backend contact.

## Failure isolation (no cross-backend retry)

- [ ] Proxy down → both unavailable.
- [ ] PG down → both unavailable.
- [ ] Pooler down → DIRECT available, POOLED unavailable.
- [ ] Pooled auth failure → DIRECT available.
- [ ] Direct backend TLS failure → POOLED independently routable.

## Limits + perf

- [ ] Max conns enforced (excess refused, no backend socket).
- [ ] Handshake/backend/first-byte timeouts enforced.
- [ ] Malformed input never creates a backend socket.
- [ ] Bench 100/500/1000 conns: p50/p99 connect, TPS, proxy CPU/RSS,
      backend + pooler server-conn counts vs internal baseline.

## Regression

- [ ] Full Maven suite green; S-07/S-09 lifecycle intact.
- [ ] OmniDB management stays direct/private; Mongo/MySQL unchanged.
- [ ] No public 5432/6432; structured builders only, no URL surgery.

## S-06.1 adversarial hardening (implemented, live re-proven)

- Cancel map entries are unregistered when their relay exits (previously
  `del` was never called; entries lived to the 100k cap). Cancel learning
  additionally stops at the first backend ReadyForQuery so result-row bytes
  can never plant bogus mappings. Unknown/wrong-secret cancels still fail
  closed with no backend contact.
- StartupMessage rewrite excises only the routing span and preserves all
  other option bytes exactly (previously Fields+Join collapsed repeated
  whitespace inside quoted values).
- `go vet` + `go test -race` clean; `FuzzExtractMode` (2.7M execs) and
  `FuzzRewriteNoLeak` (4.4M execs) PASS with no crashers; live matrix,
  cancel, fail-closed, and pooler-down isolation re-proven after the fix.

## Accepted non-blocking hardening (S-06.2, not a correctness requirement)

- Per-IP rate limiting is intentionally absent. Abuse is bounded by:
  `PROXY_MAX_CONNS` (default 2000, fail-closed, no backend contact on
  refuse), 10s handshake/read deadlines (slow-loris reaped), 16 KiB
  StartupMessage cap, no backend socket before SNI + mode validation, and
  fail-closed routing. Per-IP fairness remains a future hardening item.
