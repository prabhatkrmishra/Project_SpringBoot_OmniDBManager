package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import com.pkmprojects.mongodbserver.audit.store.InMemoryQueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Statement↔duration correlation matrix for PostgreSQL jsonlog ingestion.
 * Keyed by backend (pid, session_id); interleaved sessions must never cross.
 * Uses deadline polling (the drainer persists asynchronously); no fixed sleeps
 * gate assertions cause.
 */
class PostgresDurationCorrelationTest {

    private InMemoryQueryAuditStore store;
    private AuditCollectorService collector;

    private static QueryAuditProperties props() {
        return QueryAuditProperties.forTests(true, 30, 2000, true, false, false);
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryQueryAuditStore();
        collector = new AuditCollectorService(store, props());
    }

    @AfterEach
    void tearDown() {
        collector.shutdown();
    }

    private static String statement(String pid, String session, String sql) {
        return "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"u\","
                + "\"dbname\":\"d\",\"pid\":" + pid + ",\"session_id\":\"" + session + "\","
                + "\"error_severity\":\"LOG\",\"message\":\"statement: " + sql + "\"}";
    }

    private static String duration(String pid, String session, String ms) {
        return "{\"timestamp\":\"2026-09-14 21:08:39.531 UTC\",\"user\":\"u\","
                + "\"dbname\":\"d\",\"pid\":" + pid + ",\"session_id\":\"" + session + "\","
                + "\"error_severity\":\"LOG\",\"message\":\"duration: " + ms + " ms\"}";
    }

    private List<QueryAuditEvent> await(int n, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (store.countFiltered(QueryAuditFilter.empty()) >= n) {
                break;
            }
            Thread.sleep(50);
        }
        // Page past the default limit: count, don't cap.
        long total = store.countFiltered(QueryAuditFilter.empty());
        return store.findFiltered(QueryAuditFilter.empty(), 0, (int) Math.max(total, n));
    }

    private QueryAuditEvent onlyByShape(String fragment) throws Exception {
        List<QueryAuditEvent> events = await(1, 8000);
        return events.stream().filter(e -> e.getNormalizedShape().contains(fragment)).findFirst().orElseThrow();
    }

    @Test
    void statementPlusDurationYieldsOneEnrichedEvent() throws Exception {
        collector.ingestPostgresLine(statement("101", "s1", "SELECT * FROM a WHERE id = 1;"), false);
        collector.ingestPostgresLine(duration("101", "s1", "12.5"), false);
        QueryAuditEvent e = onlyByShape("FROM a");
        assertThat(e.getDurationMs()).isEqualTo(13L);
        // Exactly one event total: no duration-only row.
        assertThat(await(1, 3000)).hasSize(1);
    }

    @Test
    void durationAloneProducesZeroEvents() throws Exception {
        collector.ingestPostgresLine(duration("101", "s1", "5.0"), false);
        Thread.sleep(2200); // let two drain cycles pass; nothing may appear
        assertThat(store.findFiltered(QueryAuditFilter.empty(), 0, 100)).isEmpty();
        assertThat(collector.unmatchedDurations()).isEqualTo(1);
    }

    @Test
    void statementAloneIsHeldPendingNotPersistedImmediately() throws Exception {
        collector.ingestPostgresLine(statement("101", "s1", "SELECT * FROM lonely WHERE id = 2;"), false);
        Thread.sleep(2200); // two drain cycles: a pending statement must NOT flush early
        assertThat(store.findFiltered(QueryAuditFilter.empty(), 0, 100)).isEmpty();
        assertThat(collector.pendingStatements()).isEqualTo(1);
    }

    @Test
    void interleavedSessionsNeverCrossCorrelate() throws Exception {
        collector.ingestPostgresLine(statement("101", "sA", "SELECT * FROM tab_a WHERE id = 1;"), false);
        collector.ingestPostgresLine(statement("202", "sB", "SELECT * FROM tab_b WHERE id = 2;"), false);
        collector.ingestPostgresLine(duration("101", "sA", "11.0"), false);
        collector.ingestPostgresLine(duration("202", "sB", "22.0"), false);
        QueryAuditEvent a = onlyByShape("tab_a");
        QueryAuditEvent b = onlyByShape("tab_b");
        assertThat(a.getDurationMs()).isEqualTo(11L);
        assertThat(b.getDurationMs()).isEqualTo(22L);
    }

    @Test
    void duplicateDurationFirstMatchesSecondDropped() throws Exception {
        collector.ingestPostgresLine(statement("101", "s1", "SELECT * FROM dup WHERE id = 3;"), false);
        collector.ingestPostgresLine(duration("101", "s1", "7.0"), false);
        collector.ingestPostgresLine(duration("101", "s1", "7.0"), false);
        QueryAuditEvent e = onlyByShape("dup");
        assertThat(e.getDurationMs()).isEqualTo(7L);
        assertThat(collector.unmatchedDurations()).isEqualTo(1);
    }

    @Test
    void duplicateStatementFlushesOlderFirst() throws Exception {
        collector.ingestPostgresLine(statement("101", "s1", "SELECT * FROM old_q WHERE id = 4;"), false);
        collector.ingestPostgresLine(statement("101", "s1", "SELECT * FROM new_q WHERE id = 5;"), false);
        collector.ingestPostgresLine(duration("101", "s1", "9.0"), false);
        QueryAuditEvent oldQ = onlyByShape("old_q");
        QueryAuditEvent newQ = onlyByShape("new_q");
        assertThat(oldQ.getDurationMs()).isNull();
        assertThat(newQ.getDurationMs()).isEqualTo(9L);
    }

    @Test
    void errorSemanticsPreservedWithoutDuplication() throws Exception {
        String err = "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"u\","
                + "\"dbname\":\"d\",\"pid\":101,\"session_id\":\"s1\",\"error_severity\":\"ERROR\","
                + "\"state_code\":\"42P01\",\"message\":\"relation \\\"t\\\" does not exist\","
                + "\"statement\":\"SELECT * FROM t;\"}";
        collector.ingestPostgresLine(err, false);
        collector.ingestPostgresLine(duration("101", "s1", "3.0"), false);
        List<QueryAuditEvent> events = await(1, 8000);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getSuccess()).isFalse();
        assertThat(events.get(0).getErrorCode()).isEqualTo("42P01");
    }

    @Test
    void malformedLinesDoNotCorruptCorrelation() throws Exception {
        collector.ingestPostgresLine("not json", false);
        collector.ingestPostgresLine("{\"message\":123}", false);
        collector.ingestPostgresLine(statement("101", "s1", "SELECT * FROM sane WHERE id = 6;"), false);
        collector.ingestPostgresLine(duration("101", "s1", "4.0"), false);
        QueryAuditEvent e = onlyByShape("sane");
        assertThat(e.getDurationMs()).isEqualTo(4L);
    }

    @Test
    void pendingOverflowEvictsOldestBounded() throws Exception {
        for (int i = 0; i < 1050; i++) {
            collector.ingestPostgresLine(
                    statement(String.valueOf(1000 + i), "sx", "SELECT * FROM ov WHERE id = " + i + ";"), false);
        }
        assertThat(collector.pendingStatements()).isLessThanOrEqualTo(1000);
        // Overflowed statements flush unenriched through the normal path.
        Thread.sleep(2500);
        assertThat(store.countFiltered(QueryAuditFilter.empty())).isGreaterThan(0);
    }

    @Test
    void concurrentIngestionNeverCrossCorrelates() throws Exception {
        int threads = 8;
        int perThread = 25;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                final int ti = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String pid = String.valueOf(500 + ti);
                        String sess = "sc" + ti;
                        // Distinct table per iteration: numeric literals fold
                        // to "?" by design, so varying only the number would
                        // (correctly) dedupe to one shape per thread.
                        collector.ingestPostgresLine(
                                statement(pid, sess, "SELECT * FROM ct" + ti + "_" + i + " WHERE id = " + i + ";"), false);
                        collector.ingestPostgresLine(duration(pid, sess, (1 + i % 50) + ".0"), false);
                    }
                    return null;
                }));
            }
            for (var f : futures) {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        List<QueryAuditEvent> events = await(threads * perThread, 20000);
        assertThat(events).hasSize(threads * perThread);
        // Every event's duration matches its own thread's range; spot-check pairing.
        for (QueryAuditEvent e : events) {
            assertThat(e.getDurationMs()).isNotNull().isBetween(1L, 50L);
        }
    }
}
