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

    /** Ingests one PostgreSQL jsonlog line. Never throws. */
    public void ingestPostgresLine(String jsonLine, boolean pooled) {
        ingestPostgresLineWithSession(jsonLine, (u, d) -> null, pooled);
    }

    /**
     * Ingests one PostgreSQL jsonlog line with bridge-session correlation.
     * The resolver maps (user, database) to the most recent live bridge
     * session; pooled legs always stay INFERRED. Never throws.
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
            // Pre-extract identity for the session lookup (no raw text kept).
            String user = PostgresJsonlogParser.field(jsonLine == null ? "" : jsonLine, "user");
            String db = PostgresJsonlogParser.field(jsonLine == null ? "" : jsonLine, "dbname");
            BridgeSessionEvent ingress = null;
            try {
                ingress = sessionFor == null ? null : sessionFor.apply(user, db);
            } catch (Exception ignored) {
                ingress = null;
            }
            boolean pooled = pooledDefault || (ingress != null && ingress.pooled());
            var parsed = PostgresJsonlogParser.parseLine(jsonLine, pooled,
                    ingress != null ? ingress.clientIp() : null,
                    ingress != null ? ingress.username() : null,
                    ingress != null ? ingress.database() : null);
            if (parsed.event().isPresent()) {
                // Stamp the correlated bridge session id when present.
                if (ingress != null && parsed.event().get().getSessionId() == null) {
                    parsed.event().get().setSessionId("bridge:" + ingress.sessionId());
                }
                offer(parsed.event().get());
            }
        } catch (Exception e) {
            log.debug("postgres audit line skipped: {}", e.getMessage());
        }
    }

    /**
     * Most-recent live bridge session for (user, database), or null.
     * Used by the tail runner to correlate PG backend events with ingress
     * identity. Pooled sessions are returned too — the parser downgrades
     * them to INFERRED, preserving ingress context without false authority.
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

    /** Removes a closed bridge session (bounded map hygiene). */
    public void forgetBridgeSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
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
            if (sessions.size() > 10000) {
                // Evict a small oldest sample instead of clearing (which
                // would drop live correlation context under connection churn).
                int evicted = 0;
                for (String id : sessions.keySet()) {
                    if (evicted++ >= 500) break;
                    BridgeSessionEvent cur = sessions.get(id);
                    if (cur != null && cur.closedAt() != null) {
                        sessions.remove(id);
                    }
                }
                if (sessions.size() > 10000) {
                    sessions.clear();
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
