# Cutover Gate — single-port Database Proxy, pooled-only public (:14291)

Deployment: `db.missionhelmai.com:14291` → Nginx stream (TLS passthrough,
SNI-routed) → PgBouncer `:6432` → PostgreSQL `:5432`. This is the ONLY
public database route. Direct PostgreSQL has NO public route (loopback
on-box, SSH tunnel from outside) — one hostname cannot serve both links
because SNI routing cannot split it.

Do NOT hand out pooled connection strings until every box below is checked
with live evidence. The proxy stays disabled until then
(`DATABASE_PROXY_ENABLED=false` default, `PublicEndpointConsistencyGuard`
rejects half-configured proxy blocks at startup).

## Preconditions (inputs)

- [ ] Prod `DATABASE_PROXY_POOLED_HOST=db.missionhelmai.com` decided
      (`direct-host` left blank = pooled-only public).
- [ ] Certificate for `db.missionhelmai.com` installed (`./certs/`).
- [ ] AWS SG approval: expose ONLY TCP `:14291` (`0.0.0.0/0`);
      `5432`/`6432`/`9813`/`9815` denied externally (verified, not assumed).

## Cutover verification (in order — stop on first failure)

1. [ ] Start Database Proxy.
2. [ ] Proxy health: process up + `pgbouncer:6432` reachable.
3. [ ] `db.missionhelmai.com:14291` → PgBouncer → PostgreSQL
       (tenant pooled login, `SELECT 1`).
4. [ ] Unknown SNI → REJECTED, no fallback backend (proxy SNI-failure audit).
5. [ ] TLS cert/SNI correctness for `db.missionhelmai.com`.
6. [ ] Tenant A pooled → database A succeeds.
7. [ ] Tenant A → database B DENIED.
8. [ ] External `5432` blocked.
9. [ ] External `6432` blocked.
10. [ ] External `9813` blocked.
11. [ ] External `9815` blocked.
12. [ ] External `14291` with wrong SNI → rejected.
13. [ ] OmniDB management (provision/explore/backup/monitor) functional
        on-box — direct loopback path only.
14. [ ] Kill PgBouncer → on-box direct provision/reset/delete still work;
        public pooled correctly unavailable (accepted: single public link
        is a single public point of failure; direct was never public).
15. [ ] Stop PostgreSQL → pooled reports unavailable (no false-healthy).
16. [ ] Password rotation: old password fails new connections, new password
        succeeds via pooled public (and direct loopback on-box).
17. [ ] Database deletion with an ACTIVE pooled connection (old sessions
        gone, no stale backend after recreate).
18. [ ] Database recreation rejects old credentials (pooled public),
        accepts new ones.
19. [ ] Only after 1–18 pass: hand out
        `postgresql://user:pass@db.missionhelmai.com:14291/db?sslmode=require`
        strings to apps.

## PgBouncer public TLS (pooled route) — required before cutover

Background: the proxy uses TLS passthrough, so the client TLS session
terminates at PgBouncer, not PostgreSQL. The pooler MUST have
`client_tls_sslmode=require` + cert/key (compose `pgbouncer-init` writes
this block only when `DATABASE_PROXY_ENABLED=true` with the pooled hostname
set; missing cert files fail the init container CLOSED, never plaintext).
The pooler→PostgreSQL leg is a separate session
(`server_tls_sslmode=require`; verify-full deliberately NOT used — the
pooler dials internal `postgres`, uncovered by the public SAN).

T1. [ ] `db.missionhelmai.com:14291` reaches PgBouncer through Nginx SNI
        routing (tenant pooled login, `SELECT 1`).
T2. [ ] Rendered `/etc/pgbouncer/pgbouncer.ini` contains
        `client_tls_sslmode = require` + `client_tls_cert_file` +
        `client_tls_key_file` + `client_tls_protocols = secure`.
T3. [ ] PgBouncer presents the expected certificate
        (`openssl s_client -connect db.missionhelmai.com:14291 -servername
        db.missionhelmai.com`), files owned 70:70, key mode 600.
T4. [ ] Pooled client with `sslmode=require` authenticates (positive).
T5. [ ] Pooled client with `sslmode=disable` FAILS (negative — proves the
        public TLS boundary is actually enforced, not assumed).
T6. [ ] PgBouncer → PostgreSQL uses TLS (`server_tls_sslmode=require` in
        the rendered ini; pooled queries succeed while PG requires SSL).
T7. [ ] Backend cert policy recorded: `require` (encryption, no hostname
        check) — verify-full explicitly deferred (internal hostname).
T8. [ ] Same-identity proof: same user/password/db via pooled-public and
        via direct-loopback on-box; `SELECT current_user,
        current_database()` returns the same pair on both (hostname selects
        transport mode, NOT tenant authorization).
T9. [ ] Unknown SNI on `:14291` rejected with TLS untouched (no default
        backend; complements check 4 with explicit TLS-level evidence).

## Validation hierarchy (what proves what)

Infrastructure validation (proxy/backend reachability)
  -> PgBouncer validation (SHOW POOLS: pooler alive only, NOT tenant proof)
  -> Tenant pooled validation, LOOPBACK tier (tenant → pooler → PG via
     127.0.0.1:6432; proves SCRAM + auth_query, NOT DNS/NSG/proxy/SNI)
  -> Tenant pooled validation, PUBLIC tier (tenant → public DNS → NSG →
     proxy :14291 → SNI → pooler → PG). ONLY this proves the app contract.

ConnectionValidationService.validatePooledDetailed reports the tier;
provision/repair log a warning whenever health rests on LOOPBACK alone.
