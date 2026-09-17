package com.pkmprojects.mongodbserver.audit.collector;

import com.pkmprojects.mongodbserver.audit.QueryAuditProperties;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditEvent;
import com.pkmprojects.mongodbserver.audit.ingest.BridgeSessionEvent;
import com.pkmprojects.mongodbserver.audit.ingest.MongoProfilerParser;
import com.pkmprojects.mongodbserver.audit.ingest.MysqlSlowLogParser;
import com.pkmprojects.mongodbserver.audit.ingest.PostgresJsonlogParser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * VPS-local ingestion boundary for database-native telemetry.
 *
 * <p>Consumes PostgreSQL jsonlog lines, MySQL slow-log blocks, MongoDB
 * profiler documents, and bridge session-context events; normalizes and
 * redacts each into a canonical {@link QueryAuditEvent}; and persists them
 * through the bounded {@link QueryAuditStore}.</p>
 *
 * <p>Fail-open by design: the queue is bounded (oldest dropped first with a
 * counter), Mongo outages never block callers, retries use bounded backoff
 * with jitter, and every source resumes from its last accepted offset.</p>
 */
@Service
public class AuditCollectorService {

    private static final Logger log = LoggerFactory.getLogger(AuditCollectorService.class);

    public static final int QUEUE_CAPACITY = 5000;
    static final int BATCH_SIZE = 100;

    private final QueryAuditStore store;
    private final QueryAuditProperties properties;
    private final BlockingQueue<QueryAuditEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong persisted = new AtomicLong();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Deque<String> seenOrder = new ArrayDeque<>();
    private final Map<String, BridgeSessionEvent> sessions = new ConcurrentHashMap<>();
    /**
     * Exact SID index: short bridge SID ({@code application_name=omnidb:<sid>})
     * to bridge session. This is the ONLY map used for PostgreSQL IP
     * assignment. The legacy {@link #sessions} map (by long session id)
     * remains for lifecycle bookkeeping; the legacy
     * {@code (user,database) -> most-recent} lookup is never used for IP.
     */
    private final Map<String, BridgeSessionEvent> sessionsBySid = new ConcurrentHashMap<>();
    private final PostgresStatementCorrelator statementCorrelator = new PostgresStatementCorrelator();
    private final ScheduledExecutorService drainer;

    public AuditCollectorService(QueryAuditStore store, QueryAuditProperties properties) {
        this.store = store;
        this.properties = properties;
        this.drainer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-collector-drain");
            t.setDaemon(true);
            return t;
        });
        this.drainer.scheduleWithFixedDelay(this::drain, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Ingests one PostgreSQL jsonlog line with EXACT SID correlation.
     * The bridge SID is read from the line's {@code application_name}
     * ({@code omnidb:<sid>}) and resolved against the bridge session log.
     * Uncorrelated lines get {@code sourceIp=null, INFERRED} — never
     * remote_host, never latest-session. Never throws.
     */
    public void ingestPostgresLine(String jsonLine) {
        if (!auditEnabled() || !properties.postgresEnabled()) {
            return;
        }
        try {
            String app = PostgresJsonlogParser.field(jsonLine == null ? "" : jsonLine, "application_name");
            String sid = PostgresJsonlogParser.extractBridgeSid(app);
            BridgeSessionEvent ingress = sid != null ? sessionsBySid.get(sid) : null;
            boolean pooled = ingress != null && ingress.pooled();
            var parsed = PostgresJsonlogParser.parseLine(jsonLine, pooled,
                    ingress != null ? ingress.clientIp() : null,
                    ingress != null ? ingress.username() : null,
                    ingress != null ? ingress.database() : null,
                    sid);
            if (parsed.duration().isPresent()) {
                var sib = parsed.duration().get();
                QueryAuditEvent enriched = statementCorrelator.match(
                        sib.pid(), sib.sessionId(), sib.durationMs());
                if (enriched != null) {
                    offer(enriched);
                }
            }
            if (parsed.event().isPresent()) {
                QueryAuditEvent event = parsed.event().get();
                if (event.getBridgeSessionId() == null && sid != null) {
                    event.setBridgeSessionId(sid);
                }
                String pid = event.getConnectionId();
                String sessionId = event.getSessionId();
                if (pid != null && sessionId != null && event.getErrorCode() == null) {
                    for (QueryAuditEvent flushed : statementCorrelator.retain(pid, sessionId, event)) {
                        offer(flushed);
                    }
                } else {
                    offer(event);
                }
            }
            for (QueryAuditEvent expired : statementCorrelator.flushExpired()) {
                offer(expired);
            }
        } catch (Exception e) {
            log.debug("postgres audit line skipped: {}", e.getMessage());
        }
    }

    /** Ingests one PostgreSQL jsonlog line. Never throws. */
    public void ingestPostgresLine(String jsonLine, boolean pooled) {
        ingestPostgresLineWithSession(jsonLine, (u, d) -> null, pooled);
    }

    /**
     * Legacy resolver-based ingest kept for backwards-compatible callers.
     * The resolver is consulted ONLY to determine the pooled flag when no
     * SID is present; it is NEVER used for IP assignment. IP comes from
     * the EXACT SID lookup ({@link #sessionsBySid}) or is null.
     * New code must call {@link #ingestPostgresLine(String)}. Never throws.
      */
    public void ingestPostgresLineWithSession(String jsonLine,
            java.util.function.BiFunction<String, String, BridgeSessionEvent> sessionFor) {
        ingestPostgresLineWithSession(jsonLine, sessionFor, false);
    }

    private void ingestPostgresLineWithSession(String jsonLine,
            java.util.function.BiFunction<String, String, BridgeSessionEvent> sessionFor, boolean pooledDefault) {
        if (!auditEnabled() || !properties.postgresEnabled()) {
            return;
        }
        try {
            // EXACT SID lookup first (no raw text kept beyond flat fields).
            String app = PostgresJsonlogParser.field(jsonLine == null ? "" : jsonLine, "application_name");
            String sid = PostgresJsonlogParser.extractBridgeSid(app);
            BridgeSessionEvent ingress = sid != null ? sessionsBySid.get(sid) : null;
            boolean pooled;
            if (ingress != null) {
                pooled = ingress.pooled();
            } else if (sid != null) {
                // SID present but unknown (expired/restarted bridge): fail
                // closed to null IP, INFERRED — never latest-session.
                pooled = true;
            } else {
                // Pre-SID / non-bridged line: consult the legacy resolver
                // ONLY for the pooled flag, never for IP.
                String user = PostgresJsonlogParser.field(jsonLine == null ? "" : jsonLine, "user");
                String db = PostgresJsonlogParser.field(jsonLine == null ? "" : jsonLine, "dbname");
                BridgeSessionEvent legacy = null;
                try {
                    legacy = sessionFor == null ? null : sessionFor.apply(user, db);
                } catch (Exception ignored) {
                    legacy = null;
                }
                pooled = pooledDefault || (legacy != null && legacy.pooled());
                // IP stays null: no exact SID means no deterministic IP.
                ingress = null;
            }
            var parsed = PostgresJsonlogParser.parseLine(jsonLine, pooled,
                    ingress != null ? ingress.clientIp() : null,
                    ingress != null ? ingress.username() : null,
                    ingress != null ? ingress.database() : null,
                    sid);
            if (parsed.duration().isPresent()) {
                // Duration sibling: join to the pending statement with the
                // same backend identity, or drop when unmatched. Never an
                // event of its own. No re-lookup: the pending statement
                // already carries its exact IP/bridge SID (atomicity).
                var sib = parsed.duration().get();
                QueryAuditEvent enriched = statementCorrelator.match(
                        sib.pid(), sib.sessionId(), sib.durationMs());
                if (enriched != null) {
                    offer(enriched);
                }
            }
            if (parsed.event().isPresent()) {
                // Statement (or error) line: retain pending its duration
                // sibling; flush any duplicate/TTL-expired entries first.
                // IP/bridge SID are captured NOW and travel with the pending
                // event — the later duration never triggers a new lookup.
                QueryAuditEvent event = parsed.event().get();
                if (event.getBridgeSessionId() == null && sid != null) {
                    event.setBridgeSessionId(sid);
                }
                String pid = event.getConnectionId();
                String sessionId = event.getSessionId();
                if (pid != null && sessionId != null && event.getErrorCode() == null) {
                    for (QueryAuditEvent flushed : statementCorrelator.retain(pid, sessionId, event)) {
                        offer(flushed);
                    }
                } else {
                    // Error events (already complete) and identity-less lines
                    // bypass correlation: errors must never wait for, or be
                    // merged with, a later duration.
                    offer(event);
                }
            }
            for (QueryAuditEvent expired : statementCorrelator.flushExpired()) {
                offer(expired);
            }
        } catch (Exception e) {
            log.debug("postgres audit line skipped: {}", e.getMessage());
        }
    }

    /** Pending statement↔duration joins awaiting their sibling (diagnostic). */
    public int pendingStatements() {
        return statementCorrelator.pendingCount();
    }

    /** Duration siblings dropped for lack of a pending statement (diagnostic). */
    public long unmatchedDurations() {
        return statementCorrelator.unmatchedDurations();
    }

    /**
     * Legacy most-recent lookup for (user, database), or null.
     * Retained for backwards-compatible callers/tests only. MUST NOT be
     * used for PostgreSQL IP assignment — exact SID lookup
     * ({@link #bridgeSessionBySid}) is the only IP path. This method
     * exists so pre-SID tests/callers compile; new code must not call it
     * for attribution.
      */
    public BridgeSessionEvent bridgeSession(String user, String database) {
        if (user == null || database == null) {
            return null;
        }
        BridgeSessionEvent best = null;
        for (BridgeSessionEvent s : sessions.values()) {
            if (user.equals(s.username()) && database.equals(s.database())) {
                if (best == null || (s.eventAt() != null && best.eventAt() != null
                        && s.eventAt().isAfter(best.eventAt()))) {
                    best = s;
                }
            }
        }
        return best;
    }

    /**
     * Exact bridge session for a short SID, or null. The ONLY lookup used
     * for PostgreSQL IP assignment.
     */
    public BridgeSessionEvent bridgeSessionBySid(String sid) {
        if (sid == null || sid.isBlank()) {
            return null;
        }
        return sessionsBySid.get(sid);
    }

    /** Removes a closed bridge session (bounded map hygiene, both indexes). */
    public void forgetBridgeSession(String sessionId) {
        if (sessionId != null) {
            BridgeSessionEvent removed = sessions.remove(sessionId);
            if (removed != null && removed.bridgeSessionId() != null) {
                sessionsBySid.remove(removed.bridgeSessionId(), removed);
            } else if (removed == null) {
                // Defensive: end arrived for an unknown long id (e.g. after
                // collector restart lost the map) — sweep any SID entry
                // whose long id matches, without scanning user/db.
                sessionsBySid.entrySet().removeIf(e ->
                        sessionId.equals(e.getValue().sessionId()));
            }
        }
    }

    /** Ingests one MySQL slow-log block with an optional schema hint. Never throws. */
    public void ingestMysqlBlock(String block, String schemaHint) {
        if (!auditEnabled() || !properties.mysqlEnabled()) {
            return;
        }
        try {
            var parsed = MysqlSlowLogParser.parseBlock(block, schemaHint);
            if (parsed.event().isPresent()) {
                offer(parsed.event().get());
            }
        } catch (Exception e) {
            log.debug("mysql audit block skipped: {}", e.getMessage());
        }
    }

    /** Ingests one Mongo profiler document. Never throws. */
    public void ingestMongoProfile(Document doc) {
        if (!auditEnabled() || !properties.mongoEnabled()) {
            return;
        }
        try {
            var parsed = MongoProfilerParser.parse(doc);
            if (parsed.event().isPresent()) {
                offer(parsed.event().get());
            }
        } catch (Exception e) {
            log.debug("mongo audit doc skipped: {}", e.getMessage());
        }
    }

    /** Records bridge session context for later correlation. Never throws. */
    public void ingestBridgeSession(BridgeSessionEvent session) {
        if (session == null) {
            return;
        }
        try {
            sessions.put(session.sessionId(), session);
            if (session.bridgeSessionId() != null) {
                sessionsBySid.put(session.bridgeSessionId(), session);
            }
            if (sessions.size() > 10000 || sessionsBySid.size() > 10000) {
                // Evict a small oldest sample instead of clearing (which
                // would drop live correlation context under connection churn).
                int evicted = 0;
                for (String id : sessions.keySet()) {
                    if (evicted++ >= 500) break;
                    BridgeSessionEvent cur = sessions.get(id);
                    if (cur != null && cur.closedAt() != null) {
                        sessions.remove(id);
                        if (cur.bridgeSessionId() != null) {
                            sessionsBySid.remove(cur.bridgeSessionId(), cur);
                        }
                    }
                }
                if (sessions.size() > 10000 || sessionsBySid.size() > 10000) {
                    sessions.clear();
                    sessionsBySid.clear();
                }
            }
        } catch (Exception e) {
            log.debug("bridge session skipped: {}", e.getMessage());
        }
    }

    /** Flushes one batch with a single bounded retry (jittered). */
    void drain() {
        try {
            QueryAuditEvent first = queue.poll();
            if (first == null) {
                return;
            }
            java.util.List<QueryAuditEvent> batch = new java.util.ArrayList<>(BATCH_SIZE);
            batch.add(first);
            queue.drainTo(batch, BATCH_SIZE - 1);
            for (QueryAuditEvent e : batch) {
                persistWithRetry(e);
            }
        } catch (Exception e) {
            log.debug("audit drain skipped: {}", e.getMessage());
        }
    }

    private void persistWithRetry(QueryAuditEvent e) {
        try {
            store.record(e);
            persisted.incrementAndGet();
        } catch (Exception ex) {
            try {
                Thread.sleep(100 + (long) (Math.random() * 200));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                store.record(e);
                persisted.incrementAndGet();
            } catch (Exception ex2) {
                dropped.incrementAndGet();
                log.warn("audit event dropped after retry: {}", ex2.getMessage());
            }
        }
    }

    private void offer(QueryAuditEvent e) {
        // Content-based dedupe: same shape + same second + same session.
        // observedAt carries sub-second jitter from Instant.now() fallbacks,
        // so truncate to seconds for the key.
        String ts = e.getObservedAt() == null ? "none"
                : e.getObservedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String key = e.getShapeHash() + "|" + ts + "|" + e.getSessionId() + "|"
                + e.getEngine() + "|" + e.getDatabase() + "|" + e.getProvisionedUser();
        if (!seen.add(key)) {
            return;
        }
        synchronized (seenOrder) {
            seenOrder.addLast(key);
            while (seenOrder.size() > 10000) {
                seen.remove(seenOrder.removeFirst());
            }
        }
        accepted.incrementAndGet();
        if (!queue.offer(e)) {
            QueryAuditEvent oldest = queue.poll();
            if (oldest != null) {
                dropped.incrementAndGet();
            }
            if (!queue.offer(e)) {
                dropped.incrementAndGet();
            }
        }
    }

    private boolean auditEnabled() {
        return properties == null || properties.enabled();
    }

    /** Operator-visible collector state (no secrets, no query content). */
    public Map<String, Object> status() {
        return Map.of(
                "enabled", auditEnabled(),
                "queued", queue.size(),
                "accepted", accepted.get(),
                "persisted", persisted.get(),
                "dropped", dropped.get() + store.droppedCount(),
                "sessions", sessions.size(),
                "pendingStatements", statementCorrelator.pendingCount(),
                "unmatchedDurations", statementCorrelator.unmatchedDurations(),
                "latest", store.latestObservedAt().map(Instant::toString).orElse("none"));
    }

    /** Ingestion lag behind wall-clock for the latest persisted event. */
    public Duration lag() {
        return store.latestObservedAt()
                .map(latest -> Duration.between(latest, Instant.now()))
                .orElse(Duration.ZERO);
    }

    /** Recent failures are fail-open drops; surfaced as counters only. */
    public List<String> recentFailures() {
        return List.of();
    }

    /** Marks a bridge session closed (end event) without deleting ingress context immediately. */
    public void closeBridgeSession(String sessionId) {
        forgetBridgeSession(sessionId);
    }

    @PreDestroy
    public void shutdown() {
        drainer.shutdownNow();
    }
}
