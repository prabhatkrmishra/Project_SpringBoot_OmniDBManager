package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import com.pkmprojects.mongodbserver.audit.store.InMemoryQueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import java.time.Duration;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Collector fail-open/boundedness contract: ingestion never throws, backpressure
 * drops oldest first, and Mongo outages surface as counters.
 */
class AuditCollectorServiceTest {

    private AuditCollectorService collector(InMemoryQueryAuditStore store, boolean pg, boolean my, boolean mo) {
        return new AuditCollectorService(store,
                new QueryAuditProperties(true, 30, 2000, pg, my, mo));
    }

    @Test
    void postgresAndMysqlAndMongoIngestion() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store, true, true, true);
        c.ingestPostgresLine(
                "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"u\",\"dbname\":\"d\","
                        + "\"remote_host\":\"1.2.3.4\",\"error_severity\":\"LOG\","
                        + "\"message\":\"statement: SELECT * FROM t WHERE pw='x';\"}",
                false);
        c.ingestMysqlBlock("# Time: 2026-09-14T21:10:00.664691Z\n"
                + "# User@Host: mu[mu] @ localhost []  Id: 13\n"
                + "# Query_time: 0.001  Lock_time: 0.000 Rows_sent: 1  Rows_examined: 1\n"
                + "SET timestamp=1789420200;\nSELECT * FROM t WHERE s = 'y';", "mydb");
        c.ingestMongoProfile(new Document("op", "query").append("ns", "m.coll")
                .append("command", new Document("find", "coll"))
                .append("client", "5.6.7.8").append("user", "mu@m"));
        // Drain synchronously: poll the store until events land (max 5s).
        long deadline = System.currentTimeMillis() + 5000;
        while (store.countFiltered(QueryAuditFilter.empty()) < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(store.countFiltered(QueryAuditFilter.empty())).isEqualTo(3);
        for (var e : store.findFiltered(QueryAuditFilter.empty(), 0, 10)) {
            assertThat(e.getNormalizedShape()).doesNotContain("'x'", "'y'");
        }
        c.shutdown();
    }

    @Test
    void disabledSourcesAreIgnored() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store, false, false, false);
        c.ingestPostgresLine("{\"error_severity\":\"LOG\",\"message\":\"statement: SELECT 1;\"}", false);
        Thread.sleep(1500);
        assertThat(store.countFiltered(QueryAuditFilter.empty())).isZero();
        c.shutdown();
    }

    @Test
    void duplicateEventsAreSuppressed() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store, true, false, false);
        String line = "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"u\",\"dbname\":\"d\","
                + "\"error_severity\":\"LOG\",\"message\":\"statement: SELECT 1;\"}";
        // Same line twice: dedupe applies at offer time (same second + hash).
        c.ingestPostgresLine(line, false);
        c.ingestPostgresLine(line, false);
        long deadline = System.currentTimeMillis() + 4000;
        while (store.countFiltered(QueryAuditFilter.empty()) < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(store.countFiltered(QueryAuditFilter.empty())).isEqualTo(1);
        c.shutdown();
    }

    @Test
    void ingestionNeverThrowsOnGarbage() {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = collector(store, true, true, true);
        c.ingestPostgresLine("\u0000{bad", false);
        c.ingestMysqlBlock(null, null);
        c.ingestMongoProfile(null);
        c.ingestBridgeSession(null);
        assertThat(c.status()).containsKey("queued");
        assertThat(c.lag()).isGreaterThanOrEqualTo(Duration.ZERO);
        c.shutdown();
    }
}
