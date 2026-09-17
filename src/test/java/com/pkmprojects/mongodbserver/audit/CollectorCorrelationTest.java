package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import com.pkmprojects.mongodbserver.audit.ingest.BridgeSessionEvent;
import com.pkmprojects.mongodbserver.audit.store.InMemoryQueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * EXACT bridge-SID correlation: PostgreSQL events carry
 * application_name=omnidb:{sid} and resolve to the single originating
 * bridge session. Same-user concurrent clients never cross-correlate;
 * uncorrelated lines become sourceIp=null/INFERRED (never pooler IP,
 * never latest-session, never AUTHORITATIVE).
 */
class CollectorCorrelationTest {

    private static final String SID_DIRECT = "aaaabbbbcccc";
    private static final String SID_POOLED = "dddd11112222";
    private static final String SID_A = "aaaa1111bbbb";
    private static final String SID_B = "bbbb2222cccc";
    private static final String SID_C = "cccc3333dddd";

    private AuditCollectorService collector(InMemoryQueryAuditStore store) {
        AuditCollectorService c = new AuditCollectorService(store,
                QueryAuditProperties.forTests(true, 30, 2000, true, false, false));
        return c;
    }

    private BridgeSessionEvent session(String id, String sid, String ip, String user, String db,
            String mode, String profile, String route) {
        return new BridgeSessionEvent(Instant.now(), id, ip, 51234,
                user, db, mode, profile, route,
                null, Instant.now(), null, sid);
    }

    private String statement(String user, String db, int pid, String pgSession, String app, String sql) {
        return "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"" + user + "\","
                + "\"dbname\":\"" + db + "\",\"pid\":" + pid + ",\"session_id\":\"" + pgSession + "\","
                + "\"remote_host\":\"10.9.9.9\","
                + "\"application_name\":\"" + app + "\","
                + "\"error_severity\":\"LOG\",\"message\":\"statement: " + sql + "\"}";
    }

    private String duration(String user, String db, int pid, String pgSession, String app, String ms) {
        return "{\"timestamp\":\"2026-09-14 21:08:39.531 UTC\",\"user\":\"" + user + "\","
                + "\"dbname\":\"" + db + "\",\"pid\":" + pid + ",\"session_id\":\"" + pgSession + "\","
                + "\"remote_host\":\"10.9.9.9\","
                + "\"application_name\":\"" + app + "\","
                + "\"error_severity\":\"LOG\",\"message\":\"duration: " + ms + " ms\"}";
    }

    private void awaitCount(InMemoryQueryAuditStore store, long n) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (store.countFiltered(QueryAuditFilter.empty()) < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    void directSessionCorrelatesAuthoritative() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        c.ingestBridgeSession(session("s-direct", SID_DIRECT, "203.0.113.7",
                "shop_user", "shop", "direct", "none", "direct"));
        c.ingestPostgresLine(statement("shop_user", "shop", 132, "6aa86257.84",
                "omnidb:" + SID_DIRECT, "SELECT * FROM t WHERE id = 1;"));
        c.ingestPostgresLine(duration("shop_user", "shop", 132, "6aa86257.84",
                "omnidb:" + SID_DIRECT, "2.5"));
        awaitCount(store, 1);
        var e = store.findFiltered(QueryAuditFilter.empty(), 0, 1).get(0);
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.AUTHORITATIVE);
        assertThat(e.getAuditConfidence()).isEqualTo(AuditConfidence.HIGH);
        assertThat(e.getSourceIp()).isEqualTo("203.0.113.7");
        assertThat(e.getBridgeSessionId()).isEqualTo(SID_DIRECT);
        assertThat(e.getDurationMs()).isEqualTo(3L);
        c.shutdown();
    }

    @Test
    void pooledSessionStaysInferredWithExactIp() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        c.ingestBridgeSession(session("s-pooled", SID_POOLED, "203.0.113.9",
                "shop_user", "shop", "pooled", "standard", "pooled"));
        c.ingestPostgresLine(statement("shop_user", "shop", 55, "6aa86257.85",
                "omnidb:" + SID_POOLED, "SELECT * FROM t WHERE id = 2;"));
        c.ingestPostgresLine(duration("shop_user", "shop", 55, "6aa86257.85",
                "omnidb:" + SID_POOLED, "4.2"));
        awaitCount(store, 1);
        var e = store.findFiltered(QueryAuditFilter.empty(), 0, 1).get(0);
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.INFERRED);
        assertThat(e.getAuditConfidence()).isEqualTo(AuditConfidence.MEDIUM);
        assertThat(e.getSourceIp()).isEqualTo("203.0.113.9");
        assertThat(e.getBridgeSessionId()).isEqualTo(SID_POOLED);
        assertThat(e.getDurationMs()).isEqualTo(4L);
        c.shutdown();
    }

    @Test
    void sameUserConcurrentClientsNeverCrossCorrelate() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        // Same user/database, three concurrent bridge sessions, distinct IPs.
        // C opened last — the old most-recent heuristic would stamp every
        // statement with IP-C. Exact SID lookup must keep A->A, B->B, C->C
        // even though all statements share one PG backend PID (pooler reuse).
        c.ingestBridgeSession(session("s-a", SID_A, "192.0.2.11", "u", "d", "pooled", "standard", "pooled"));
        c.ingestBridgeSession(session("s-b", SID_B, "192.0.2.22", "u", "d", "pooled", "standard", "pooled"));
        c.ingestBridgeSession(session("s-c", SID_C, "192.0.2.33", "u", "d", "pooled", "standard", "pooled"));
        // Interleaved statements on backend pid 100, distinct PG sessions per txn.
        c.ingestPostgresLine(statement("u", "d", 100, "pg.A", "omnidb:" + SID_A, "SELECT * FROM ta WHERE id = 1;"));
        c.ingestPostgresLine(statement("u", "d", 100, "pg.B", "omnidb:" + SID_B, "SELECT * FROM tb WHERE id = 2;"));
        c.ingestPostgresLine(statement("u", "d", 100, "pg.C", "omnidb:" + SID_C, "SELECT * FROM tc WHERE id = 3;"));
        c.ingestPostgresLine(duration("u", "d", 100, "pg.A", "omnidb:" + SID_A, "5.0"));
        c.ingestPostgresLine(duration("u", "d", 100, "pg.B", "omnidb:" + SID_B, "6.0"));
        c.ingestPostgresLine(duration("u", "d", 100, "pg.C", "omnidb:" + SID_C, "7.0"));
        awaitCount(store, 3);
        List<QueryAuditEvent> events = store.findFiltered(QueryAuditFilter.empty(), 0, 10);
        assertThat(events).hasSize(3);
        for (QueryAuditEvent e : events) {
            assertThat(e.getAttribution()).isEqualTo(QueryAttribution.INFERRED);
            if (SID_A.equals(e.getBridgeSessionId())) {
                assertThat(e.getSourceIp()).isEqualTo("192.0.2.11");
            } else if (SID_B.equals(e.getBridgeSessionId())) {
                assertThat(e.getSourceIp()).isEqualTo("192.0.2.22");
            } else if (SID_C.equals(e.getBridgeSessionId())) {
                assertThat(e.getSourceIp()).isEqualTo("192.0.2.33");
            } else {
                throw new AssertionError("missing bridge SID on " + e.getNormalizedShape());
            }
            // Pooler/backend address must never appear as tenant IP.
            assertThat(e.getSourceIp()).isNotEqualTo("10.9.9.9");
        }
        c.shutdown();
    }

    @Test
    void durationKeepsStatementIpWhenNewSessionOpens() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        c.ingestBridgeSession(session("s-a", SID_A, "192.0.2.11", "u", "d", "pooled", "standard", "pooled"));
        c.ingestPostgresLine(statement("u", "d", 101, "pg.X", "omnidb:" + SID_A, "SELECT * FROM atomic_t WHERE id = 1;"));
        // B opens after A's statement but before A's duration: the duration
        // must NOT trigger a new lookup — the event keeps SID-A/IP-A.
        c.ingestBridgeSession(session("s-b", SID_B, "192.0.2.22", "u", "d", "pooled", "standard", "pooled"));
        c.ingestPostgresLine(duration("u", "d", 101, "pg.X", "omnidb:" + SID_A, "9.0"));
        awaitCount(store, 1);
        var e = store.findFiltered(QueryAuditFilter.empty(), 0, 1).get(0);
        assertThat(e.getBridgeSessionId()).isEqualTo(SID_A);
        assertThat(e.getSourceIp()).isEqualTo("192.0.2.11");
        assertThat(e.getDurationMs()).isEqualTo(9L);
        c.shutdown();
    }

    @Test
    void unknownMissingAndMalformedSidAreNullAndInferred() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        // Unknown SID (never seen): null, INFERRED, never latest-session.
        c.ingestBridgeSession(session("s-a", SID_A, "192.0.2.11", "u", "d", "pooled", "standard", "pooled"));
        c.ingestPostgresLine(statement("u", "d", 200, "pg.U", "omnidb:ffffffffffff", "SELECT * FROM ghost WHERE id = 1;"));
        c.ingestPostgresLine(duration("u", "d", 200, "pg.U", "omnidb:ffffffffffff", "3.0"));
        // Missing SID (pre-SID client, bare omnidb): null, INFERRED.
        c.ingestPostgresLine(
                "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"u\","
                        + "\"dbname\":\"d\",\"pid\":201,\"session_id\":\"pg.V\","
                        + "\"remote_host\":\"10.9.9.9\",\"application_name\":\"omnidb\","
                        + "\"error_severity\":\"LOG\",\"message\":\"statement: SELECT * FROM bare WHERE id = 2;\"}");
        c.ingestPostgresLine(
                "{\"timestamp\":\"2026-09-14 21:08:39.531 UTC\",\"user\":\"u\","
                        + "\"dbname\":\"d\",\"pid\":201,\"session_id\":\"pg.V\","
                        + "\"remote_host\":\"10.9.9.9\",\"application_name\":\"omnidb\","
                        + "\"error_severity\":\"LOG\",\"message\":\"duration: 4.0 ms\"}");
        // Malformed SID: treated as missing.
        c.ingestPostgresLine(statement("u", "d", 202, "pg.W", "omnidb:NOT-VALID!!", "SELECT * FROM bad WHERE id = 3;"));
        c.ingestPostgresLine(duration("u", "d", 202, "pg.W", "omnidb:NOT-VALID!!", "5.0"));
        awaitCount(store, 3);
        for (QueryAuditEvent e : store.findFiltered(QueryAuditFilter.empty(), 0, 10)) {
            assertThat(e.getSourceIp()).isNull();
            assertThat(e.getAttribution()).isEqualTo(QueryAttribution.INFERRED);
            assertThat(e.getAuditConfidence()).isEqualTo(AuditConfidence.MEDIUM);
        }
        c.shutdown();
    }

    @Test
    void sessionEndCleansBothIndexes() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        c.ingestBridgeSession(session("s-x", SID_A, "192.0.2.11", "u", "d", "direct", "none", "direct"));
        assertThat(c.bridgeSessionBySid(SID_A)).isNotNull();
        c.forgetBridgeSession("s-x");
        assertThat(c.bridgeSessionBySid(SID_A)).isNull();
        assertThat(c.bridgeSession("u", "d")).isNull();
        c.shutdown();
    }

    @Test
    void unknownSessionFallsBackWithoutFalseAuthority() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        // No bridge session and no SID: pooler address must never become IP.
        c.ingestPostgresLine(
                "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"ghost\","
                        + "\"dbname\":\"ghostdb\",\"pid\":9,\"session_id\":\"6aa86257.99\","
                        + "\"remote_host\":\"127.0.0.1\","
                        + "\"error_severity\":\"LOG\",\"message\":\"statement: SELECT 1;\"}");
        c.ingestPostgresLine(
                "{\"timestamp\":\"2026-09-14 21:08:39.531 UTC\",\"user\":\"ghost\","
                        + "\"dbname\":\"ghostdb\",\"pid\":9,\"session_id\":\"6aa86257.99\","
                        + "\"remote_host\":\"127.0.0.1\","
                        + "\"error_severity\":\"LOG\",\"message\":\"duration: 1.0 ms\"}");
        awaitCount(store, 1);
        var e = store.findFiltered(QueryAuditFilter.empty(), 0, 1).get(0);
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.INFERRED);
        assertThat(e.getSourceIp()).isNull();
        c.shutdown();
    }
}
