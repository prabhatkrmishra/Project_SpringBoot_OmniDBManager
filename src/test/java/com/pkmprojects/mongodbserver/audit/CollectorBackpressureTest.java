package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import com.pkmprojects.mongodbserver.audit.store.InMemoryQueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Fail-open proof: a Mongo outage blocks nothing, the queue stays bounded,
 * drops are counted, and ingestion resumes after recovery.
 */
class CollectorBackpressureTest {

    /** Store that fails until released (simulated Mongo outage). */
    static class FlappingStore implements QueryAuditStore {
        final AtomicBoolean down = new AtomicBoolean(true);
        final java.util.concurrent.CopyOnWriteArrayList<QueryAuditEvent> kept =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicLong storeDrops = new java.util.concurrent.atomic.AtomicLong();

        private QueryAuditEvent event(String shape) {
            QueryAuditEvent e = new QueryAuditEvent();
            e.setEventId(UUID.randomUUID().toString());
            e.setObservedAt(Instant.now());
            e.setEngine(com.pkmprojects.mongodbserver.model.DatabaseEngineType.POSTGRES);
            e.setDatabase("d");
            e.setProvisionedUser("u");
            e.setSourceIp("1.1.1.1");
            e.setOperationClass("read");
            e.setCommandType("select");
            e.setNormalizedShape(shape);
            e.setShapeHash(QueryShapeRedactor.shapeHash(shape));
            e.setAttribution(QueryAttribution.INFERRED);
            e.setAuditConfidence(AuditConfidence.MEDIUM);
            e.setObservationSource(ObservationSource.POSTGRES_JSONLOG);
            return e;
        }

        @Override
        public void record(QueryAuditEvent event) {
            if (down.get()) {
                storeDrops.incrementAndGet();
                return; // fail-open drop, counted
            }
            kept.add(event);
        }

        @Override
        public List<QueryAuditEvent> findFiltered(QueryAuditFilter filter, int skip, int limit) {
            return kept.stream().skip(skip).limit(limit).toList();
        }

        @Override
        public long countFiltered(QueryAuditFilter filter) {
            return kept.size();
        }

        @Override
        public long droppedCount() {
            return storeDrops.get();
        }

        @Override
        public Optional<Instant> latestObservedAt() {
            return kept.stream().map(QueryAuditEvent::getObservedAt).max(java.util.Comparator.naturalOrder());
        }
    }

    @Test
    void outageDropsAreCountedAndRecoveryResumes() throws Exception {
        FlappingStore store = new FlappingStore();
        AuditCollectorService c = new AuditCollectorService(store,
                new QueryAuditProperties(true, 30, 2000, true, false, false));
        // Flood during outage: 6000 offers into a 5000 queue.
        for (int i = 0; i < 6000; i++) {
            final int n = i;
            c.ingestPostgresLine(
                    "{\"error_severity\":\"LOG\",\"message\":\"statement: SELECT " + n + ";\"}", false);
        }
        // Ingestion never blocks (returns immediately); drain drops fail-open.
        Thread.sleep(4000);
        var status = c.status();
        long dropped = (long) status.get("dropped");
        assertThat(dropped).isGreaterThan(0);

        // Recovery: new events persist.
        store.down.set(false);
        for (int i = 0; i < 10; i++) {
            c.ingestPostgresLine(
                    "{\"error_severity\":\"LOG\",\"message\":\"statement: SELECT 'x';\"}", false);
        }
        long deadline = System.currentTimeMillis() + 8000;
        while (store.countFiltered(QueryAuditFilter.empty()) < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(store.countFiltered(QueryAuditFilter.empty())).isGreaterThan(0);
        c.shutdown();
    }

    @Test
    void queueNeverExceedsBound() {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        AuditCollectorService c = new AuditCollectorService(store,
                new QueryAuditProperties(true, 30, 2000, true, false, false));
        for (int i = 0; i < 12000; i++) {
            c.ingestPostgresLine(
                    "{\"error_severity\":\"LOG\",\"message\":\"statement: SELECT " + i + ";\"}", false);
        }
        assertThat((int) c.status().get("queued")).isLessThanOrEqualTo(AuditCollectorService.QUEUE_CAPACITY);
        c.shutdown();
    }
}
