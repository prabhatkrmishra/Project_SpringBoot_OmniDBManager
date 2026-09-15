package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import com.pkmprojects.mongodbserver.audit.ingest.BridgeSessionEvent;
import com.pkmprojects.mongodbserver.audit.store.InMemoryQueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Bridge-session correlation: direct legs upgrade to AUTHORITATIVE/HIGH,
 * pooled legs stay INFERRED even with full ingress context, and session-end
 * events clean the correlation map.
 */
class CollectorCorrelationTest {

    private AuditCollectorService collector(InMemoryQueryAuditStore store) {
        AuditCollectorService c = new AuditCollectorService(store,
                QueryAuditProperties.forTests(true, 30, 2000, true, false, false));
        return c;
    }

    private BridgeSessionEvent session(String id, String user, String db, String mode, String profile) {
        return new BridgeSessionEvent(Instant.now(), id, "203.0.113.7", 51234,
                user, db, mode, profile, mode.equals("pooled") ? "pooled" : "direct",
                null, Instant.now(), null);
    }

    private void awaitCount(InMemoryQueryAuditStore store, long n) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (store.countFiltered(QueryAuditFilter.empty()) < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    void directSessionCorrelatesAuthoritative() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        c.ingestBridgeSession(session("s-direct", "shop_user", "shop", "direct", "none"));
        c.ingestPostgresLineWithSession(
                "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"shop_user\","
                        + "\"dbname\":\"shop\",\"pid\":132,\"remote_host\":\"127.0.0.1\","
                        + "\"error_severity\":\"LOG\",\"message\":\"statement: SELECT * FROM t WHERE id = 1;\"}",
                c::bridgeSession);
        awaitCount(store, 1);
        var e = store.findFiltered(QueryAuditFilter.empty(), 0, 1).get(0);
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.AUTHORITATIVE);
        assertThat(e.getAuditConfidence()).isEqualTo(AuditConfidence.HIGH);
        assertThat(e.getSourceIp()).isEqualTo("203.0.113.7");
        c.shutdown();
    }

    @Test
    void pooledSessionStaysInferredWithIngressContext() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        c.ingestBridgeSession(session("s-pooled", "shop_user", "shop", "pooled", "standard"));
        c.ingestPostgresLineWithSession(
                "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"shop_user\","
                        + "\"dbname\":\"shop\",\"pid\":55,\"remote_host\":\"127.0.0.1\","
                        + "\"error_severity\":\"LOG\",\"message\":\"statement: SELECT * FROM t WHERE id = 2;\"}",
                c::bridgeSession);
        awaitCount(store, 1);
        var e = store.findFiltered(QueryAuditFilter.empty(), 0, 1).get(0);
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.INFERRED);
        // Ingress context preserved but never presented as backend authority.
        assertThat(e.getSourceIp()).isEqualTo("203.0.113.7");
        c.shutdown();
    }

    @Test
    void sessionEndCleansCorrelationMap() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        c.ingestBridgeSession(session("s-x", "u", "d", "direct", "none"));
        assertThat(c.bridgeSession("u", "d")).isNotNull();
        c.forgetBridgeSession("s-x");
        assertThat(c.bridgeSession("u", "d")).isNull();
        c.shutdown();
    }

    @Test
    void unknownSessionFallsBackWithoutFalseAuthority() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store);
        // No bridge session: backend address must not become authoritative.
        c.ingestPostgresLineWithSession(
                "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"ghost\","
                        + "\"dbname\":\"ghostdb\",\"pid\":9,\"remote_host\":\"127.0.0.1\","
                        + "\"error_severity\":\"LOG\",\"message\":\"statement: SELECT 1;\"}",
                c::bridgeSession);
        awaitCount(store, 1);
        var e = store.findFiltered(QueryAuditFilter.empty(), 0, 1).get(0);
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.INFERRED);
        c.shutdown();
    }
}
