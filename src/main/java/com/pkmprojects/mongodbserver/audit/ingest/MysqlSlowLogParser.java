package com.pkmprojects.mongodbserver.audit.ingest;

import com.pkmprojects.mongodbserver.audit.AuditConfidence;
import com.pkmprojects.mongodbserver.audit.ObservationSource;
import com.pkmprojects.mongodbserver.audit.QueryAttribution;
import com.pkmprojects.mongodbserver.audit.QueryAuditEvent;
import com.pkmprojects.mongodbserver.audit.QueryShapeRedactor;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses MySQL 8.4 slow-log per-statement blocks into canonical audit events.
 *
  * <p>Proven spike shape: blocks of {@code # Time / # User@Host: user[user] @
 * host [ip] Id: N / # Query_time / SET timestamp= / <statement>}. The
 * {@code User@Host} identity is database-native and authoritative per
 * connection (no pooling in the MySQL topology): concurrent clients with
 * the same username keep distinct {@code [ip]} values, so A/B cannot
 * inherit each other's IP. The IP bracket is empty for local-socket
 * sessions; hostnames are never persisted as {@code sourceIp} and never
 * DNS-resolved — uncorrelated IPs become null/INFERRED.</p>
 */
public final class MysqlSlowLogParser {

    private static final Pattern USER_HOST = Pattern.compile(
            "# User@Host:\\s*(\\S+?)\\[(.*?)\\]\\s+@\\s+(\\S+)(?:\\s+\\[(.*?)\\])?");
    private static final Pattern QUERY_TIME = Pattern.compile(
            "# Query_time:\\s*([0-9.]+)\\s+Lock_time:\\s*([0-9.]+)\\s+Rows_sent:\\s*(\\d+)\\s+Rows_examined:\\s*(\\d+)");
    private static final Pattern SET_TS = Pattern.compile("SET timestamp=(\\d+);");

    /**
     * System schemas where manager/tooling {@code root} activity is
     * control-plane surface. A {@code root} user with a non-system tenant
     * schemaHint is genuine tenant traffic and MUST remain auditable.
     */
    private static final java.util.Set<String> SYSTEM_SCHEMAS = java.util.Set.of(
            "mysql", "sys", "information_schema", "performance_schema");

    private MysqlSlowLogParser() {
    }

    public record Parsed(Optional<QueryAuditEvent> event, boolean malformed) {
    }

    /**
     * Parses one slow-log block (the lines from {@code # Time:} through the
     * statement text). Unknown schema falls back to the connection's current
     * schema hint when provided.
      */
    public static Parsed parseBlock(String block, String schemaHint) {
        if (block == null || block.isBlank()) {
            return new Parsed(Optional.empty(), true);
        }
        String[] lines = block.split("\n");
        String timeLine = null;
        String userHostLine = null;
        String statsLine = null;
        StringBuilder sql = new StringBuilder();
        boolean inSql = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("# Time:")) {
                timeLine = line.substring("# Time:".length()).trim();
            } else if (line.startsWith("# User@Host:")) {
                userHostLine = line;
            } else if (line.startsWith("# Query_time:")) {
                statsLine = line;
            } else if (line.startsWith("SET timestamp=") || line.startsWith("#")
                    || line.startsWith("use ") || line.startsWith("USE ")) {
                inSql = false;
                if ((line.startsWith("use ") || line.startsWith("USE ")) && schemaHint == null) {
                    // 'use db;' selector line — not a statement.
                }
                continue;
            } else if (!line.isEmpty()) {
                if (sql.length() > 0) {
                    sql.append('\n');
                }
                sql.append(raw.trim());
                inSql = true;
            }
        }
        if (userHostLine == null && statsLine == null && sql.length() == 0) {
            return new Parsed(Optional.empty(), true);
        }
        String user = null;
        String host = null;
        String ip = null;
        if (userHostLine != null) {
            Matcher m = USER_HOST.matcher(userHostLine);
            if (m.find()) {
                user = emptyToNull(m.group(1));
                host = emptyToNull(m.group(3));
                ip = emptyToNull(m.group(4));
            }
        }
        Double querySecs = null;
        Long rowsSent = null;
        Long rowsExamined = null;
        if (statsLine != null) {
            Matcher m = QUERY_TIME.matcher(statsLine);
            if (m.find()) {
                querySecs = parseDouble(m.group(1));
                rowsSent = parseLong(m.group(3));
                rowsExamined = parseLong(m.group(4));
            }
        }
        String statement = sql.toString().trim();
        if (statement.isEmpty() || statement.startsWith("# administrator command:")) {
            return new Parsed(Optional.empty(), false);
        }
        // Identity-safe control-plane exclusion: username alone MUST NOT
        // drop tenant traffic. A root/mysql.sys user is infrastructure
        // surface ONLY when it is not demonstrably tenant-originated —
        // i.e. bound to a system schema (or no tenant schema at all).
        // A tenant provisioned as root with a genuine tenant schemaHint
        // remains fully auditable.
        if (user != null && (user.equals("root") || user.equals("mysql.sys"))) {
            String schemaKey = schemaHint == null ? null : schemaHint.trim().toLowerCase();
            if (schemaKey == null || schemaKey.isBlank() || SYSTEM_SCHEMAS.contains(schemaKey)) {
                return new Parsed(Optional.empty(), false);
            }
        }
        QueryAuditEvent e = new QueryAuditEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setObservedAt(parseTime(timeLine));
        e.setEngine(DatabaseEngineType.MYSQL);
        e.setDatabase(schemaHint);
        if (schemaHint != null) {
            e.setManagedDatabaseId(DatabaseEngineType.MYSQL.name() + ":" + schemaHint);
        }
        e.setProvisionedUser(user);
        // Strict IP rule: authoritative IP literal only; hostnames,
        // loopback, and blanks become null (never hostname-as-IP, never
        // DNS, never 127.0.0.1 as tenant IP).
        String safeIp = SourceIpRules.sanitizeTenantIp(ip != null ? ip : host);
        e.setSourceIp(safeIp);
        e.setSourcePort(null);
        if (safeIp != null) {
            e.setAttribution(QueryAttribution.AUTHORITATIVE);
            e.setAuditConfidence(AuditConfidence.HIGH);
        } else {
            e.setAttribution(QueryAttribution.INFERRED);
            e.setAuditConfidence(AuditConfidence.MEDIUM);
        }
        String normalized = QueryShapeRedactor.normalize(statement);
        e.setNormalizedShape(cap(normalized));
        e.setShapeHash(QueryShapeRedactor.shapeHash(e.getNormalizedShape()));
        String cmd = QueryShapeRedactor.commandType(e.getNormalizedShape());
        e.setCommandType(cmd);
        e.setOperationClass(QueryShapeRedactor.operationClass(cmd));
        e.setDurationMs(querySecs == null ? null : Math.round(querySecs * 1000));
        e.setRowsReturned(rowsSent);
        e.setRowsAffected(rowsExamined);
        e.setSuccess(null);
        e.setObservationSource(ObservationSource.MYSQL_SLOW_LOG);
        return new Parsed(Optional.of(e), false);
    }

    private static String cap(String s) {
        return s.length() > QueryShapeRedactor.MAX_NORMALIZED_LENGTH
                ? s.substring(0, QueryShapeRedactor.MAX_NORMALIZED_LENGTH) : s;
    }

    static Instant parseTime(String raw) {
        if (raw == null) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (DateTimeParseException e) {
            Matcher m = SET_TS.matcher(raw);
            if (m.find()) {
                try {
                    return Instant.ofEpochSecond(Long.parseLong(m.group(1)));
                } catch (NumberFormatException ignored) {
                    return Instant.now();
                }
            }
            return Instant.now();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
