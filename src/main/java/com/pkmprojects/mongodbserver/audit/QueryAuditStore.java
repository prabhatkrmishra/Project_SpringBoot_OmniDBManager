package com.pkmprojects.mongodbserver.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for {@code query_audit}. Mongo-backed when the Mongo
 * engine is enabled, bounded in-memory otherwise. Fail-open by contract:
 * storage failures must never block tenant database traffic.
 */
public interface QueryAuditStore {

    /** Persists one event; silently drops (counting the drop) on failure. */
    void record(QueryAuditEvent event);

    /** Newest-first page with optional filters; empty filters match all. */
    List<QueryAuditEvent> findFiltered(QueryAuditFilter filter, int skip, int limit);

    /** Count of events matching the filter. */
    long countFiltered(QueryAuditFilter filter);

    /** Number of events dropped due to backpressure or storage failure. */
    long droppedCount();

    /** Latest successfully persisted event, if any. */
    Optional<Instant> latestObservedAt();

    /** Filter for the operator query-activity view. All fields optional. */
    record QueryAuditFilter(
            String engine,
            String databaseContains,
            String userContains,
            String sourceIp,
            String operationClass,
            Boolean success,
            String attribution,
            String confidence,
            String shapeHash,
            Instant from,
            Instant to) {
        public static QueryAuditFilter empty() {
            return new QueryAuditFilter(null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
