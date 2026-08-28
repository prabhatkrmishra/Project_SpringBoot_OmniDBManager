package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.MysqlMonitorSnapshot;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlMonitorService {

    private static final Logger log = LoggerFactory.getLogger(MysqlMonitorService.class);

    private final MysqlDatabaseRepository mysqlRepository;
    private final Clock clock;

    public MysqlMonitorService(@Autowired(required = false) MysqlDatabaseRepository mysqlRepository, Clock clock) {
        this.mysqlRepository = mysqlRepository;
        this.clock = clock;
    }

    public MysqlMonitorSnapshot getSnapshot() {
        Instant now = clock.instant();
        if (!ping()) {
            return new MysqlMonitorSnapshot(false, now, null, null, null, 0, null, null, null, null, null);
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
            Map<String, Object> data = mysqlRepository.getMysqlMonitorData();
            Object cc = data.get("connectionCount");
            if (cc instanceof Number n) connectionCount = n.intValue();
            Object up = data.get("uptimeSeconds");
            if (up instanceof Number n) uptimeSeconds = n.longValue();
            Object ver = data.get("version");
            if (ver != null) version = ver.toString();
        } catch (Exception e) {
            log.warn("Could not read MySQL monitor data", e);
        }
        if (version == null) {
            try {
                version = mysqlRepository.getVersion();
            } catch (Exception e) {
                log.warn("Could not read MySQL version for monitor", e);
            }
        }

        try {
            Map<String, Long> sizes = mysqlRepository.getDatabaseSizes();
            databaseCount = sizes.size();
            totalStorageBytes = sizes.values().stream().mapToLong(Long::longValue).sum();
        } catch (Exception e) {
            log.warn("Could not read MySQL database sizes for monitor", e);
        }

        try {
            Integer active = mysqlRepository.getJdbcTemplate().queryForObject(
                    "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND != 'Sleep'", Integer.class);
            activeConnections = active != null ? active : 0;
            Integer idle = mysqlRepository.getJdbcTemplate().queryForObject(
                    "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND = 'Sleep'", Integer.class);
            idleConnections = idle != null ? idle : 0;
        } catch (Exception e) {
            log.debug("Could not read PROCESSLIST breakdown", e);
        }
        try {
            Long committed = mysqlRepository.getJdbcTemplate().queryForObject(
                    "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Com_commit'", Long.class);
            if (committed == null) {
                try {
                    String val = mysqlRepository.getJdbcTemplate().queryForObject("SHOW GLOBAL STATUS LIKE 'Com_commit'", (rs, rn) -> rs.getString(2));
                    if (val != null) committed = Long.parseLong(val);
                } catch (Exception ignored) {}
            }
            txCommitted = committed != null ? committed : 0L;
            Long rolled = mysqlRepository.getJdbcTemplate().queryForObject(
                    "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Com_rollback'", Long.class);
            if (rolled == null) {
                try {
                    String val = mysqlRepository.getJdbcTemplate().queryForObject("SHOW GLOBAL STATUS LIKE 'Com_rollback'", (rs, rn) -> rs.getString(2));
                    if (val != null) rolled = Long.parseLong(val);
                } catch (Exception ignored) {}
            }
            txRolledBack = rolled != null ? rolled : 0L;
        } catch (Exception e) {
            log.debug("Could not read MySQL xact stats", e);
        }

        return new MysqlMonitorSnapshot(true, now, version, uptimeSeconds, connectionCount,
                databaseCount, totalStorageBytes, activeConnections, idleConnections, txCommitted, txRolledBack);
    }

    public String serialize(MysqlMonitorSnapshot s) {
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
            mysqlRepository.ping();
            return true;
        } catch (Exception e) {
            log.warn("MySQL ping failed for monitor", e);
            return false;
        }
    }

    private static String number(Number v) {
        return v == null ? "null" : String.valueOf(v);
    }
}
