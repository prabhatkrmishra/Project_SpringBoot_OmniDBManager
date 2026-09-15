package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import com.pkmprojects.mongodbserver.audit.store.InMemoryQueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Throughput benchmark for the normalize→dedupe→queue→persist path.
 * Measures the collector with a no-op-fast store so the numbers reflect
 * pipeline overhead, not Mongo latency. Prints a machine-readable summary
 * line consumed by the S-17.1 report.
 */
class CollectorBenchmarkTest {

    @Test
    void benchmarkIngestionRates() throws Exception {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = new AuditCollectorService(store,
                QueryAuditProperties.forTests(true, 30, 2000, true, true, true));

        String pgLine = "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"bench\","
                + "\"dbname\":\"benchdb\",\"pid\":1,\"remote_host\":\"10.0.0.1\","
                + "\"error_severity\":\"LOG\",\"message\":\"statement: SELECT * FROM orders WHERE id = 42 AND s = 'x';\"}";
        String myBlock = "# Time: 2026-09-14T21:10:00Z\n# User@Host: bench[bench] @ host [10.0.0.2]  Id: 1\n"
                + "# Query_time: 0.002  Lock_time: 0.000 Rows_sent: 5  Rows_examined: 50\n"
                + "SET timestamp=1;\nSELECT * FROM orders WHERE id = 42;";
        Document moDoc = new Document("op", "query").append("ns", "benchdb.orders")
                .append("command", new Document("find", "orders")
                        .append("filter", new Document("id", 42)))
                .append("ts", new java.util.Date()).append("client", "10.0.0.3")
                .append("user", "bench@benchdb").append("millis", 2L).append("nreturned", 5L);

        // Warmup.
        for (int i = 0; i < 500; i++) {
            c.ingestPostgresLine(pgLine, false);
        }
        Thread.sleep(1500);

        int target = 3000;
        long t0 = System.nanoTime();
        for (int i = 0; i < target; i++) {
            // Distinct table names defeat both dedupe and literal folding
            // (numbers fold to "?" by design) so the full path is measured.
            c.ingestPostgresLine(pgLine.replace("orders", "orders_" + i), false);
            if (i % 3 == 0) c.ingestMysqlBlock(myBlock.replace("orders", "orders_" + i), "benchdb");
            if (i % 5 == 0) c.ingestMongoProfile(moDoc);
        }
        long offerNanos = System.nanoTime() - t0;

        long deadline = System.currentTimeMillis() + 30000;
        while (store.countFiltered(QueryAuditFilter.empty()) < 1000 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        Thread.sleep(2500); // let the 1s drainer finish in-flight batches
        long persisted = store.countFiltered(QueryAuditFilter.empty());
        var status = c.status();
        long dropped = (long) status.get("dropped");
        List<QueryAuditEvent> sample = store.findFiltered(QueryAuditFilter.empty(), 0, 200);
        long bytes = 0;
        for (QueryAuditEvent e : sample) {
            bytes += e.getNormalizedShape() == null ? 0 : e.getNormalizedShape().length();
        }
        long avgBytes = sample.isEmpty() ? 0 : bytes / sample.size();
        double offerQps = target / (offerNanos / 1_000_000_000.0);
        System.out.println("BENCH qps_offer=" + Math.round(offerQps)
                + " persisted=" + persisted + " dropped=" + dropped
                + " avg_shape_bytes=" + avgBytes
                + " queue=" + status.get("queued"));
        // Pipeline must sustain well above 100 QPS offer rate on this box.
        assertThat(offerQps).isGreaterThan(500);
        assertThat(persisted).isGreaterThan(1000);
        c.shutdown();
    }

    @Test
    void repeatedShapesDedupeEfficiently() {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = new AuditCollectorService(store,
                QueryAuditProperties.forTests(true, 30, 2000, true, false, false));
        String line = "{\"error_severity\":\"LOG\",\"message\":\"statement: SELECT 1;\"}";
        long t0 = System.nanoTime();
        for (int i = 0; i < 20000; i++) {
            c.ingestPostgresLine(line, false);
        }
        long nanos = System.nanoTime() - t0;
        System.out.println("BENCH dedupe_20k_ms=" + nanos / 1_000_000
                + " queued=" + c.status().get("queued"));
        assertThat(nanos / 1_000_000).isLessThan(15000);
        c.shutdown();
    }
}
