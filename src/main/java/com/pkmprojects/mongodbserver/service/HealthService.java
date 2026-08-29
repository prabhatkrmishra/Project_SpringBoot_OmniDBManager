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
import java.util.concurrent.TimeUnit;

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
        // Parallel pings with hard timeout — never block dashboard >3s per engine
        CompletableFuture<Boolean> mongoFuture = CompletableFuture.supplyAsync(this::pingMongo)
                .orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("MongoDB ping timed out or failed", e);
                    return false;
                });
        CompletableFuture<Boolean> pgFuture = CompletableFuture.supplyAsync(this::pingPostgres)
                .orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("Postgres ping timed out or failed", e);
                    return false;
                });
        CompletableFuture<Boolean> mysqlFuture = CompletableFuture.supplyAsync(this::pingMysql)
                .orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("MySQL ping timed out or failed", e);
                    return false;
                });

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
