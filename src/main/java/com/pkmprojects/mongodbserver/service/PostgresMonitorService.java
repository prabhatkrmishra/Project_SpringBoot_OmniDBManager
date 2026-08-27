package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.PostgresMonitorSnapshot;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import com.pkmprojects.mongodbserver.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Live PostgreSQL snapshots for the monitor page, from pg_stat_activity / pg_stat_database.
 */
@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PostgresMonitorService.class);

    private final PostgresDatabaseRepository postgresRepository;
    private final Clock clock;

    public PostgresMonitorService(@Autowired(required = false) PostgresDatabaseRepository postgresRepository, Clock clock) {
        this.postgresRepository = postgresRepository;
        this.clock = clock;
    }

    public PostgresMonitorSnapshot getSnapshot() {
        Instant now = clock.instant();
        if (!ping()) {
            return new PostgresMonitorSnapshot(false, now, null, null, null, 0, null, null, null, null, null);
        }

        String version = null;
        Long uptimeSeconds = null;
        Integer connectionCount = null;
        Integer activeConnections = null;
        Integer idleConnections = null;
        Long txCommitted = null;
        Long txRolledBack = null;
        int databaseCount = 0;
        Long totalStorageBytes = null;

        try {
            version = postgresRepository.getVersion();
        } catch (Exception e) {
            log.warn("Could not read Postgres version for monitor", e);
        }

        try {
            Map<String, Object> data = postgresRepository.getPostgresMonitorData();
            Object cc = data.get("connectionCount");
            if (cc instanceof Number n) connectionCount = n.intValue();
            Object up = data.get("uptimeSeconds");
            if (up instanceof Number n) uptimeSeconds = n.longValue();
            Object ver = data.get("version");
            if (ver != null) version = ver.toString();
        } catch (Exception e) {
            log.warn("Could not read Postgres monitor data", e);
        }

        try {
            Map<String, Long> sizes = postgresRepository.getDatabaseSizes();
            // filter out templates like getDatabaseSizes does
            databaseCount = (int) sizes.keySet().stream()
                    .filter(k -> !k.equals("postgres") && !k.equals("template0") && !k.equals("template1"))
                    .count();
            totalStorageBytes = sizes.values().stream().mapToLong(Long::longValue).sum();
        } catch (Exception e) {
            log.warn("Could not read Postgres database sizes for monitor", e);
        }

        // pg_stat_activity breakdown + pg_stat_database xact stats via direct queries
        try {
            // Use admin jdbcTemplate via repository helper — do inline queries here
            // Fallback: try to read from postgresRepository if available
            var jdbc = postgresRepository;
            // active/idle counts
            try {
                Integer active = jdbc.getJdbcTemplate().queryForObject(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'active'", Integer.class);
                activeConnections = active != null ? active : 0;
                Integer idle = jdbc.getJdbcTemplate().queryForObject(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'idle'", Integer.class);
                idleConnections = idle != null ? idle : 0;
            } catch (Exception e) {
                log.debug("Could not read pg_stat_activity breakdown", e);
            }
            try {
                Long committed = jdbc.getJdbcTemplate().queryForObject(
                        "SELECT COALESCE(SUM(xact_commit),0) FROM pg_stat_database", Long.class);
                txCommitted = committed != null ? committed : 0L;
                Long rolled = jdbc.getJdbcTemplate().queryForObject(
                        "SELECT COALESCE(SUM(xact_rollback),0) FROM pg_stat_database", Long.class);
                txRolledBack = rolled != null ? rolled : 0L;
            } catch (Exception e) {
                log.debug("Could not read pg_stat_database xact stats", e);
            }
        } catch (Exception e) {
            log.warn("Could not read Postgres activity stats", e);
        }

        return new PostgresMonitorSnapshot(true, now, version, uptimeSeconds, connectionCount,
                databaseCount, totalStorageBytes, activeConnections, idleConnections, txCommitted, txRolledBack);
    }

    public String serialize(PostgresMonitorSnapshot s) {
        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"reachable\":").append(s.reachable())
                .append(",\"measuredAt\":").append(Json.jsonString(s.measuredAt().toString()))
                .append(",\"version\":").append(Json.jsonString(s.version()))
                .append(",\"uptimeSeconds\":").append(number(s.uptimeSeconds()))
                .append(",\"connectionCount\":").append(number(s.connectionCount()))
                .append(",\"databaseCount\":").append(s.databaseCount())
                .append(",\"totalStorageBytes\":").append(number(s.totalStorageBytes()))
                .append(",\"activeConnections\":").append(number(s.activeConnections()))
                .append(",\"idleConnections\":").append(number(s.idleConnections()))
                .append(",\"transactionsCommitted\":").append(number(s.transactionsCommitted()))
                .append(",\"transactionsRolledBack\":").append(number(s.transactionsRolledBack()))
                .append('}');
        return json.toString();
    }

    private boolean ping() {
        try {
            postgresRepository.ping();
            return true;
        } catch (Exception e) {
            log.warn("Postgres ping failed for monitor", e);
            return false;
        }
    }

    private static String number(Number v) {
        return v == null ? "null" : String.valueOf(v);
    }
}
