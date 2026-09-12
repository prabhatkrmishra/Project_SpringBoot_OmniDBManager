# S-06 Cutover Gate — single-port Database Proxy (:15432)

Do NOT change production connection strings until every box below is
checked with live evidence. Two-port Nginx stays authoritative until then
(database.proxy.enabled=false default, PublicEndpointConsistencyGuard
rejects half-configured proxy blocks at startup).

## Preconditions (inputs)

- [ ] Prod DATABASE_PROXY_DIRECT_HOST (e.g. db.example.com) decided.
- [ ] Prod DATABASE_PROXY_POOLED_HOST (e.g. pool.example.com) decided, differs from direct.
- [ ] :15432 certificate covering BOTH hostnames (wildcard or SAN) installed.
- [ ] NSG/firewall approval: expose ONLY proxy :15432; 5432/6432/9813 denied externally.
- [ ] Proxy choice confirmed from S-05 spike (default Nginx stream+ssl_preread, fallback HAProxy).

## Cutover verification (in order — stop on first failure)

1. [ ] Start Database Proxy.
2. [ ] Proxy health: process + postgres:5432 reachable + pgbouncer:6432 reachable.
3. [ ] db.example.com:15432 -> PostgreSQL (tenant direct login, SELECT 1).
4. [ ] pool.example.com:15432 -> PgBouncer -> PostgreSQL (tenant pooled login, SELECT 1).
5. [ ] Unknown SNI -> REJECTED, no fallback to either backend (check proxy SNI-failure audit).
6. [ ] TLS cert/SNI correctness for both hostnames (verify-full where CA is used).
7. [ ] Tenant A direct -> database A succeeds.
8. [ ] Tenant A pooled -> database A succeeds.
9. [ ] Tenant A -> database B DENIED (both paths).
10. [ ] External 5432 blocked.
11. [ ] External 6432 blocked.
12. [ ] External 9813 blocked.
13. [ ] OmniDB management (provision/explore/backup/monitor) still functional — direct path only.
14. [ ] Kill PgBouncer -> direct provision/reset/delete still work; pooled correctly unavailable.
15. [ ] Stop PostgreSQL -> BOTH modes report unavailable (no false-healthy).
16. [ ] Password rotation verified through BOTH paths (old fails new-conn, new succeeds both).
17. [ ] Database deletion with an ACTIVE pooled connection (old sessions gone, no stale backend after recreate).
18. [ ] Database recreation rejects old credentials on BOTH paths, accepts new ones.
19. [ ] Only after 1-18 pass: migrate app-facing strings to :15432 endpoints.

## PgBouncer public TLS (pooled route) — required before cutover

Background: the proxy uses TLS passthrough, so the pooled TLS session
terminates at PgBouncer, not PostgreSQL. The pooler MUST have
`client_tls_sslmode=require` + cert/key (compose `pgbouncer-init` writes
this block only when `DATABASE_PROXY_ENABLED=true` + both hostnames set +
different; missing cert files fail the init container CLOSED, never
plaintext). The pooler->PostgreSQL leg is a separate session
(`server_tls_sslmode=require`; verify-full deliberately NOT used — the
pooler dials internal `postgres`, uncovered by the public SAN).

T1. [ ] pool.example.com:15432 reaches PgBouncer through Nginx SNI routing
        (tenant pooled login, SELECT 1).
T2. [ ] Rendered `/etc/pgbouncer/pgbouncer.ini` contains
        `client_tls_sslmode = require` + `client_tls_cert_file` +
        `client_tls_key_file` + `client_tls_protocols = secure`.
T3. [ ] PgBouncer presents the expected certificate for the pooled hostname
        (`openssl s_client -connect pool.example.com:15432 -servername
        pool.example.com`), owned 70:70, key mode 600.
T4. [ ] Pooled client with `sslmode=require` authenticates (positive).
T5. [ ] Pooled client with `sslmode=disable` FAILS (negative — proves the
        public TLS boundary is actually enforced, not assumed).
T6. [ ] PgBouncer -> PostgreSQL uses TLS (`server_tls_sslmode=require` in
        the rendered ini; pooled queries succeed while PG requires SSL).
T7. [ ] Backend cert policy recorded: `require` (encryption, no hostname
        check) — verify-full explicitly deferred (internal hostname).
T8. [ ] db.example.com:15432 + `sslmode=require` still reaches PostgreSQL
        directly (direct leg unaffected by pooler TLS).
T9. [ ] Same-identity proof: same user/password/db on BOTH hostnames;
        `SELECT current_user, current_database()` returns the same pair on
        both (hostname selects transport mode, NOT tenant authorization).
T10. [ ] Unknown SNI on :15432 rejected with TLS untouched (no default
        backend; complements check 5 with explicit TLS-level evidence).

## Validation hierarchy (what proves what)

Infrastructure validation (proxy/backend reachability)
  -> PgBouncer validation (SHOW POOLS: pooler alive only, NOT tenant proof)
  -> Tenant pooled validation, LOOPBACK tier (tenant -> pooler -> PG via
     127.0.0.1:6432; proves SCRAM + auth_query, NOT DNS/NSG/proxy/SNI)
  -> Tenant pooled validation, PUBLIC tier (tenant -> public DNS -> NSG ->
     proxy :15432 -> SNI -> pooler -> PG). ONLY this proves the app contract.

ConnectionValidationService.validatePooledDetailed reports the tier;
provision/repair log a warning whenever health rests on LOOPBACK alone.
