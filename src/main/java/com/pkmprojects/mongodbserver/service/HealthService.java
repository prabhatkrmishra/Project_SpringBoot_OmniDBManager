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

/**
 * Assembles server health metrics for the health dashboard.
 *
 * <p>Mongo reachability comes from {@code ping}; Postgres reachability from
 * {@code SELECT 1} when {@code app.postgres.enabled=true}. Version/uptime/
 * connections require {@code serverStatus} and degrade to {@code null} when
 * unavailable.</p>
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final Optional<PostgresDatabaseRepository> postgresRepository;
    private final boolean postgresEnabled;
    private final Optional<MysqlDatabaseRepository> mysqlRepository;
    private final boolean mysqlEnabled;

    @Autowired
    public HealthService(MongoDatabaseRepository mongoDatabaseRepository,
                         @Autowired(required = false) PostgresDatabaseRepository postgresRepository,
                         @Value("${app.postgres.enabled:false}") boolean postgresEnabled,
                         @Autowired(required = false) MysqlDatabaseRepository mysqlRepository,
                         @Value("${app.mysql.enabled:false}") boolean mysqlEnabled) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.postgresRepository = Optional.ofNullable(postgresRepository);
        this.postgresEnabled = postgresEnabled;
        this.mysqlRepository = Optional.ofNullable(mysqlRepository);
        this.mysqlEnabled = mysqlEnabled;
    }

    // Legacy 3-arg constructor for tests without MySQL
    public HealthService(MongoDatabaseRepository mongoDatabaseRepository,
                         PostgresDatabaseRepository postgresRepository,
                         boolean postgresEnabled) {
        this(mongoDatabaseRepository, postgresRepository, postgresEnabled, null, false);
    }

    public ServerHealth getHealth() {
        boolean mongoReachable = pingMongo();

        String version = null;
        Long uptimeSeconds = null;
        Integer connectionCount = null;
        if (mongoReachable) {
            try {
                Document status = mongoDatabaseRepository.getServerStatus();
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
                Map<String, Long> sizes = mongoDatabaseRepository.getDatabaseSizes();
                databaseCount = sizes.size();
                totalStorageBytes = sizes.values().stream().mapToLong(Long::longValue).sum();
            } catch (MongoException e) {
                log.warn("Could not read database sizes for health dashboard", e);
            }
        }

        boolean postgresReachable = false;
        String postgresVersion = null;
        if (postgresEnabled && postgresRepository.isPresent()) {
            try {
                postgresRepository.get().ping();
                postgresReachable = true;
                try {
                    postgresVersion = postgresRepository.get().getVersion();
                } catch (Exception e) {
                    log.debug("Could not read Postgres version", e);
                }
            } catch (Exception e) {
                log.warn("Postgres ping failed", e);
            }
        }

        boolean mysqlReachable = false;
        String mysqlVersion = null;
        if (mysqlEnabled && mysqlRepository.isPresent()) {
            try {
                mysqlRepository.get().ping();
                mysqlReachable = true;
                try {
                    mysqlVersion = mysqlRepository.get().getVersion();
                } catch (Exception e) {
                    log.debug("Could not read MySQL version", e);
                }
            } catch (Exception e) {
                log.warn("MySQL ping failed", e);
            }
        }

        return new ServerHealth(mongoReachable, version, uptimeSeconds, databaseCount, totalStorageBytes, connectionCount,
                mongoReachable, postgresReachable, postgresVersion, postgresEnabled,
                mysqlReachable, mysqlVersion, mysqlEnabled);
    }

    private boolean pingMongo() {
        try {
            mongoDatabaseRepository.ping();
            return true;
        } catch (Exception e) {
            log.warn("MongoDB ping failed", e);
            return false;
        }
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
