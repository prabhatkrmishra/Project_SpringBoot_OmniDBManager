package com.pkmprojects.mongodbserver.audit.collector;

import com.mongodb.client.MongoClient;
import com.pkmprojects.mongodbserver.audit.QueryAuditProperties;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Continuous VPS-local telemetry tails feeding the bounded
 * {@link AuditCollectorService}.
 *
 * <p>One daemon scheduler polls, in order: the PostgreSQL jsonlog file, the
 * bridge container log (session-start/end), the MySQL slow-log file, and each
 * provisioned MONGO tenant's {@code system.profile}. Every source is
 * independently enable-gated ({@code app.query-audit.*}) and independently
 * fault-isolated: one source's failure never stops the others. Poll failures
 * are counted (no secret-bearing content ever logged) and visible on the
 * operator status endpoint.</p>
 *
 * <p>Lifecycle: starts on construction when auditing is enabled; stops on
 * {@link PreDestroy}. Resume state lives in {@link CollectorOffsetStore}.
 * Graceful shutdown drains nothing (the queue persists in the collector and
 * Mongo is fail-open); offsets are saved after every poll so restart replays
 * at most one poll interval (at-least-once + dedupe).</p>
 */
@Component
public class AuditTailRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditTailRunner.class);

    static final long POLL_SECONDS = 5;

    private final AuditCollectorService collector;
    private final CollectorOffsetStore offsets;
    private final QueryAuditProperties properties;
    private final MongoClient mongoClient;
    private final ManagedDatabaseStore databaseStore;

    private final RotatingFileTailer postgresTailer;
    private final RotatingFileTailer mysqlTailer;
    private final RotatingFileTailer bridgeTailer;
    private final MysqlSlowLogBlockAssembler mysqlAssembler = new MysqlSlowLogBlockAssembler();
    private final MongoProfilerPoller profilerPoller;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong polls = new AtomicLong();
    private final AtomicLong pgLines = new AtomicLong();
    private final AtomicLong myBlocks = new AtomicLong();
    private final AtomicLong moDocs = new AtomicLong();
    private final AtomicLong sessionsSeen = new AtomicLong();
    private final AtomicLong pollErrors = new AtomicLong();
    private final AtomicReference<Instant> startedAt = new AtomicReference<>(Instant.now());
    private final AtomicReference<Instant> lastPollAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public AuditTailRunner(AuditCollectorService collector,
                           CollectorOffsetStore offsets,
                           QueryAuditProperties properties,
                           @Autowired(required = false) MongoClient mongoClient,
                           @Autowired(required = false) ManagedDatabaseStore databaseStore) {
        this.collector = collector;
        this.offsets = offsets;
        this.properties = properties;
        this.mongoClient = mongoClient;
        this.databaseStore = databaseStore;
        this.postgresTailer = new RotatingFileTailer("postgres-jsonlog",
                configuredPath("QUERY_AUDIT_PG_JSONLOG", properties == null ? null : properties.effectivePgJsonlog(),
                        "/var/lib/postgresql/log/postgresql.json"),
                offsets);
        this.mysqlTailer = new RotatingFileTailer("mysql-slowlog",
                configuredPath("QUERY_AUDIT_MYSQL_SLOWLOG", properties == null ? null : properties.effectiveMysqlSlowlog(),
                        "/var/lib/mysql/slow.log"),
                offsets);
        this.bridgeTailer = new RotatingFileTailer("bridge-sessions",
                configuredPath("QUERY_AUDIT_BRIDGE_LOG", properties == null ? null : properties.effectiveBridgeLog(),
                        "/var/log/omnidb/bridge.log"),
                offsets);
        this.profilerPoller = new MongoProfilerPoller(mongoClient, offsets);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-tail-poll");
            t.setDaemon(true);
            return t;
        });
        if (properties != null && properties.enabled()) {
            this.scheduler.scheduleWithFixedDelay(this::pollAll, 5, POLL_SECONDS, TimeUnit.SECONDS);
            log.info("audit tails started (postgres={}, mysql={}, mongo={})",
                    properties.postgresEnabled(), properties.mysqlEnabled(), properties.mongoEnabled());
        } else {
            log.info("audit tails idle (query auditing disabled)");
        }
    }

    /** One poll across all enabled sources; never throws. */
    void pollAll() {
        polls.incrementAndGet();
        lastPollAt.set(Instant.now());
        try {
            if (properties.postgresEnabled()) {
                pollPostgres();
            }
            pollBridgeSessions();
            if (properties.mysqlEnabled()) {
                pollMysql();
            }
            if (properties.mongoEnabled()) {
                pollMongo();
            }
        } catch (Exception e) {
            pollErrors.incrementAndGet();
            lastError.set(e.getClass().getSimpleName());
            log.debug("audit tail poll failed: {}", e.getMessage());
        }
    }

    private void pollPostgres() {
        try {
            // Pooled-ness is per-session (bridge route), not per-file. The
            // jsonlog alone cannot prove the leg; default to direct parsing
            // and let bridge-session correlation upgrade/downgrade below.
            // When no bridge context exists the parser marks INFERRED only
            // for pooled legs; direct legs without ingress stay MEDIUM.
            int n = postgresTailer.poll(line ->
                    collector.ingestPostgresLineWithSession(line, this::bridgeSessionFor));
            pgLines.addAndGet(n);
        } catch (Exception e) {
            pollErrors.incrementAndGet();
            lastError.set("postgres:" + e.getClass().getSimpleName());
            log.debug("postgres tail failed: {}", e.getMessage());
        }
    }

    private void pollBridgeSessions() {
        try {
            bridgeTailer.poll(line -> BridgeSessionLogTailer.feed(line,
                    s -> {
                        sessionsSeen.incrementAndGet();
                        collector.ingestBridgeSession(s);
                    },
                    collector::forgetBridgeSession));
        } catch (Exception e) {
            log.debug("bridge session tail failed: {}", e.getMessage());
        }
    }

    private void pollMysql() {
        try {
            mysqlTailer.poll(line -> mysqlAssembler.feed(line,
                    block -> {
                        myBlocks.incrementAndGet();
                        collector.ingestMysqlBlock(block, null);
                    }));
            mysqlAssembler.flush(block -> {
                myBlocks.incrementAndGet();
                collector.ingestMysqlBlock(block, null);
            });
        } catch (Exception e) {
            pollErrors.incrementAndGet();
            lastError.set("mysql:" + e.getClass().getSimpleName());
            log.debug("mysql tail failed: {}", e.getMessage());
        }
    }

    private void pollMongo() {
        try {
            for (String db : mongoTenantDatabases()) {
                List<org.bson.Document> docs = profilerPoller.pollDatabase(db, collector::ingestMongoProfile);
                moDocs.addAndGet(docs.size());
            }
        } catch (Exception e) {
            pollErrors.incrementAndGet();
            lastError.set("mongo:" + e.getClass().getSimpleName());
            log.debug("mongo profiler poll failed: {}", e.getMessage());
        }
    }

    /** Bridge session lookup for PG correlation (may return null). */
    private com.pkmprojects.mongodbserver.audit.ingest.BridgeSessionEvent bridgeSessionFor(String user, String db) {
        return collector.bridgeSession(user, db);
    }

    private List<String> mongoTenantDatabases() {
        try {
            if (databaseStore != null) {
                return databaseStore.findAllByEngineType(DatabaseEngineType.MONGO).stream()
                        .map(m -> m.getDbName()).filter(n -> n != null && !n.isBlank())
                        .filter(n -> !"admin".equals(n) && !"local".equals(n) && !"config".equals(n))
                        .toList();
            }
        } catch (Exception e) {
            log.debug("mongo tenant list failed: {}", e.getMessage());
        }
        return List.of();
    }

    private static Path configuredPath(String env, String configured, String def) {
        String raw = System.getenv(env);
        if (raw != null && !raw.isBlank()) {
            return Paths.get(raw.trim());
        }
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim());
        }
        return Paths.get(def);
    }

    /** Operator-visible tail state (paths + positions, no content). */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", !scheduler.isShutdown());
        m.put("uptime", Duration.between(startedAt.get(), Instant.now()).toString());
        m.put("polls", polls.get());
        m.put("lastPoll", lastPollAt.get() == null ? "never" : lastPollAt.get().toString());
        m.put("postgresLines", pgLines.get());
        m.put("mysqlBlocks", myBlocks.get());
        m.put("mongoDocs", moDocs.get());
        m.put("sessionsSeen", sessionsSeen.get());
        m.put("pollErrors", pollErrors.get());
        m.put("lastError", lastError.get());
        m.put("postgresPosition", postgresTailer.describePosition());
        m.put("mysqlPosition", mysqlTailer.describePosition());
        m.put("bridgePosition", bridgeTailer.describePosition());
        m.put("offsets", offsets == null ? Map.of() : offsets.snapshot());
        return m;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
