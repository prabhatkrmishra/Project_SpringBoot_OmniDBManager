package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.dto.ServerHealth;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Assembles server health metrics for the health dashboard.
 *
 * <p>Mongo reachability comes from {@code ping}; Postgres/MySQL reachability from
 * {@code SELECT 1} when enabled. Version/uptime/connections degrade to {@code null}
 * when unavailable. All pings run in parallel with a 3s timeout so a hung
 * PG/MySQL never blocks the dashboard (Phase 5).</p>
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final long PING_TIMEOUT_SECONDS = 3;

    private final Optional<MongoDatabaseRepository> mongoDatabaseRepository;
    private final boolean mongoEnabled;
    private final Optional<PostgresDatabaseRepository> postgresRepository;
    private final boolean postgresEnabled;
    private final Optional<MysqlDatabaseRepository> mysqlRepository;
    private final boolean mysqlEnabled;

    @Autowired
    public HealthService(@Autowired(required = false) MongoDatabaseRepository mongoDatabaseRepository,
                         @Value("${app.mongo.enabled:false}") boolean mongoEnabled,
                         @Autowired(required = false) PostgresDatabaseRepository postgresRepository,
                         @Value("${app.postgres.enabled:false}") boolean postgresEnabled,
                         @Autowired(required = false) MysqlDatabaseRepository mysqlRepository,
                         @Value("${app.mysql.enabled:false}") boolean mysqlEnabled) {
        this.mongoDatabaseRepository = Optional.ofNullable(mongoDatabaseRepository);
        this.mongoEnabled = mongoEnabled;
        this.postgresRepository = Optional.ofNullable(postgresRepository);
        this.postgresEnabled = postgresEnabled;
        this.mysqlRepository = Optional.ofNullable(mysqlRepository);
        this.mysqlEnabled = mysqlEnabled;
    }

    // Legacy 3-arg constructor for tests without MySQL
    public HealthService(MongoDatabaseRepository mongoDatabaseRepository,
                         PostgresDatabaseRepository postgresRepository,
                         boolean postgresEnabled) {
        this(mongoDatabaseRepository, true, postgresRepository, postgresEnabled, null, false);
    }

    public ServerHealth getHealth() {
        // Parallel pings with hard timeout — never block dashboard >3s per engine.
        // Disabled engines are not pinged at all: a future that can only return
        // false still occupies a common-pool thread, and under load it can miss
        // its own deadline and log a misleading "timed out" warning for an engine
        // that was never even contacted.
        CompletableFuture<Boolean> mongoFuture = pingAsync(mongoEnabled, this::pingMongo, "MongoDB");
        CompletableFuture<Boolean> pgFuture = pingAsync(postgresEnabled, this::pingPostgres, "Postgres");
        CompletableFuture<Boolean> mysqlFuture = pingAsync(mysqlEnabled, this::pingMysql, "MySQL");

        boolean mongoReachable = joinBool(mongoFuture);
        boolean postgresReachable = joinBool(pgFuture);
        boolean mysqlReachable = joinBool(mysqlFuture);

        String version = null;
        Long uptimeSeconds = null;
        Integer connectionCount = null;
        if (mongoReachable) {
            try {
                Document status = mongoDatabaseRepository.orElseThrow().getServerStatus();
                version = status.getString("version");
                uptimeSeconds = toLong(status.get("uptime"));
                Document connections = status.get("connections", Document.class);
                if (connections != null) {
                    Object current = connections.get("current");
                    connectionCount = current instanceof Number number ? number.intValue() : 0;
                }
            } catch (MongoException e) {
                log.warn("serverStatus unavailable for connected MongoDB (insufficient privileges?)", e);
            }
        }

        int databaseCount = 0;
        Long totalStorageBytes = null;
        if (mongoReachable) {
            try {
                Map<String, Long> sizes = mongoDatabaseRepository.orElseThrow().getDatabaseSizes();
                databaseCount = sizes.size();
                totalStorageBytes = sizes.values().stream().mapToLong(Long::longValue).sum();
            } catch (MongoException e) {
                log.warn("Could not read database sizes for health dashboard", e);
            }
        }

        String postgresVersion = null;
        if (postgresReachable) {
            try {
                postgresVersion = postgresRepository.get().getVersion();
            } catch (Exception e) {
                log.debug("Could not read Postgres version", e);
            }
        }

        String mysqlVersion = null;
        if (mysqlReachable) {
            try {
                mysqlVersion = mysqlRepository.get().getVersion();
            } catch (Exception e) {
                log.debug("Could not read MySQL version", e);
            }
        }

        return new ServerHealth(mongoReachable, version, uptimeSeconds, databaseCount, totalStorageBytes, connectionCount,
                mongoReachable, postgresReachable, postgresVersion, postgresEnabled,
                mysqlReachable, mysqlVersion, mysqlEnabled, mongoEnabled);
    }

    /**
     * Runs one engine ping with a hard timeout. A missed deadline is an expected,
     * handled outcome (cold pool, slow first connection), so it logs as a single
     * line with no stack trace. Genuine failures still log with the stack.
     */
    private CompletableFuture<Boolean> pingAsync(boolean enabled, Supplier<Boolean> ping, String engine) {
        if (!enabled) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(ping)
                .orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    if (e instanceof TimeoutException
                            || (e instanceof CompletionException && e.getCause() instanceof TimeoutException)) {
                        log.warn("{} ping timed out after {}s", engine, PING_TIMEOUT_SECONDS);
                    } else {
                        log.warn("{} ping failed", engine, e);
                    }
                    return false;
                });
    }

    private boolean pingPostgres() {
        if (!postgresEnabled || postgresRepository.isEmpty()) return false;
        try {
            postgresRepository.get().ping();
            return true;
        } catch (Exception e) {
            log.warn("Postgres ping failed", e);
            return false;
        }
    }

    private boolean pingMysql() {
        if (!mysqlEnabled || mysqlRepository.isEmpty()) return false;
        try {
            mysqlRepository.get().ping();
            return true;
        } catch (Exception e) {
            log.warn("MySQL ping failed", e);
            return false;
        }
    }

    private boolean pingMongo() {
        if (!mongoEnabled || mongoDatabaseRepository.isEmpty()) return false;
        try {
            mongoDatabaseRepository.get().ping();
            return true;
        } catch (Exception e) {
            log.warn("MongoDB ping failed", e);
            return false;
        }
    }

    private static boolean joinBool(CompletableFuture<Boolean> f) {
        try {
            return f.join();
        } catch (Exception e) {
            return false;
        }
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
