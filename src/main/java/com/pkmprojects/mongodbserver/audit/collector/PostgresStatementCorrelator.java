package com.pkmprojects.mongodbserver.audit.collector;

import com.pkmprojects.mongodbserver.audit.QueryAuditEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Joins PostgreSQL {@code statement:} lines with their sibling
 * {@code duration:} lines into single audit events.
 *
 * <p>Keyed by backend identity {@code (pid, session_id)} as logged — never by
 * timestamp alone, so interleaved sessions cannot cross-correlate. A
 * statement creates a bounded pending entry; its matching duration enriches
 * and releases exactly one event. Unmatched durations are dropped with a
 * diagnostic counter; statements that outlive the TTL flush unenriched
 * (identical to pre-correlation behavior).</p>
 *
 * <p>Pending entries hold only already-redacted event data, never raw
 * statements. All state is in-memory: a restart loses at most pending
 * durations, never creates duplicates. Thread-safe for concurrent ingest;
 * consume-on-match is atomic via {@link Map#remove}.</p>
 */
final class PostgresStatementCorrelator {

    /** Maximum pending statements; beyond this the oldest is flushed unenriched. */
    static final int MAX_PENDING = 1000;

    /** Pending statements older than this flush unenriched. */
    static final long PENDING_TTL_NANOS = 30_000_000_000L;

    /** Correlation key: backend PID + jsonlog session_id. */
    record CorrelationKey(String pid, String sessionId) {
    }

    private static final class Pending {
        final QueryAuditEvent event;
        final long createdNanos;

        Pending(QueryAuditEvent event, long createdNanos) {
            this.event = event;
            this.createdNanos = createdNanos;
        }
    }

    private final Map<CorrelationKey, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong unmatchedDurations = new AtomicLong();
    private final AtomicLong enrichedEvents = new AtomicLong();
    private final AtomicLong expiredFlushes = new AtomicLong();
    private final AtomicLong evictedOverflows = new AtomicLong();

    /**
     * Retains a parsed statement event pending its duration sibling.
     *
     * @return a previously pending event for the same key that must be
     *         flushed unenriched first (duplicate statement), or null
     */
    /**
     * Retains a parsed statement event pending its duration sibling.
     *
     * @return events to flush unenriched first: a duplicate statement for the
     *         same key (never silently discarded) plus any TTL-expired
     *         entries swept on this ingest
     */
    java.util.List<QueryAuditEvent> retain(String pid, String sessionId, QueryAuditEvent statementEvent) {
        CorrelationKey key = new CorrelationKey(pid, sessionId);
        java.util.List<QueryAuditEvent> flush = evictExpired(System.nanoTime());
        Pending prev = pending.put(key, new Pending(statementEvent, System.nanoTime()));
        if (prev != null) {
            flush.add(prev.event);
        }
        if (pending.size() > MAX_PENDING) {
            QueryAuditEvent dropped = evictOldest();
            if (dropped != null) {
                flush.add(dropped);
            }
        }
        return flush;
    }

    /**
     * Matches a duration sibling to its pending statement.
     *
     * @return the enriched event (duration set), or null when no pending
     *         statement exists — the duration is then dropped, never persisted
     */
    QueryAuditEvent match(String pid, String sessionId, long durationMs) {
        Pending p = pending.remove(new CorrelationKey(pid, sessionId));
        if (p == null) {
            unmatchedDurations.incrementAndGet();
            return null;
        }
        p.event.setDurationMs(durationMs);
        enrichedEvents.incrementAndGet();
        return p.event;
    }

    /**
     * Flushes statements older than the TTL, unenriched. Called on ingest
     * and drain activity; returns events to offer through the normal path.
     */
    java.util.List<QueryAuditEvent> flushExpired() {
        long now = System.nanoTime();
        java.util.List<QueryAuditEvent> out = new java.util.ArrayList<>();
        for (Map.Entry<CorrelationKey, Pending> e : pending.entrySet()) {
            if (now - e.getValue().createdNanos > PENDING_TTL_NANOS) {
                if (pending.remove(e.getKey(), e.getValue())) {
                    expiredFlushes.incrementAndGet();
                    out.add(e.getValue().event);
                }
            }
        }
        return out;
    }

    int pendingCount() {
        return pending.size();
    }

    long unmatchedDurations() {
        return unmatchedDurations.get();
    }

    long enrichedEvents() {
        return enrichedEvents.get();
    }

    long expiredFlushes() {
        return expiredFlushes.get();
    }

    long evictedOverflows() {
        return evictedOverflows.get();
    }

    private java.util.List<QueryAuditEvent> evictExpired(long now) {
        java.util.List<QueryAuditEvent> out = new java.util.ArrayList<>();
        if (pending.isEmpty()) {
            return out;
        }
        for (Map.Entry<CorrelationKey, Pending> e : pending.entrySet()) {
            if (now - e.getValue().createdNanos > PENDING_TTL_NANOS) {
                if (pending.remove(e.getKey(), e.getValue())) {
                    expiredFlushes.incrementAndGet();
                    out.add(e.getValue().event);
                }
            }
        }
        return out;
    }

    private QueryAuditEvent evictOldest() {
        Map.Entry<CorrelationKey, Pending> oldest = null;
        for (Map.Entry<CorrelationKey, Pending> e : pending.entrySet()) {
            if (oldest == null || e.getValue().createdNanos < oldest.getValue().createdNanos) {
                oldest = e;
            }
        }
        if (oldest != null && pending.remove(oldest.getKey(), oldest.getValue())) {
            evictedOverflows.incrementAndGet();
            // Overflow eviction FLUSHES (emits unenriched) rather than dropping:
            // the statement is real operator-visible activity; only its
            // duration is lost. Counted separately from TTL expiry.
            return oldest.getValue().event;
        }
        return null;
    }
}
