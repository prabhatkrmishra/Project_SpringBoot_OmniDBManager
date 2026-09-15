package com.pkmprojects.mongodbserver.audit.ingest;

import com.pkmprojects.mongodbserver.audit.AuditConfidence;
import com.pkmprojects.mongodbserver.audit.ObservationSource;
import com.pkmprojects.mongodbserver.audit.QueryAttribution;
import com.pkmprojects.mongodbserver.audit.QueryAuditEvent;
import com.pkmprojects.mongodbserver.audit.QueryShapeRedactor;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses PostgreSQL {@code jsonlog} statement/duration/error lines into
 * canonical audit events.
 *
 * <p>Proven spike shape (PG 18): each line carries {@code timestamp, user,
 * dbname, pid, remote_host, remote_port, session_id, ps, error_severity,
 * state_code, message, statement, application_name, backend_type}. Statement
 * lines arrive as {@code message: "statement: <sql>"} with a following
 * {@code message: "duration: N ms"} sibling sharing pid+session; error lines
 * carry {@code error_severity ERROR/FATAL} plus {@code statement} and
 * {@code state_code}.</p>
 *
 * <p>The parser never logs or stores raw statements; every event keeps only
 * the redacted shape plus its hash. Pooled traffic keeps
 * {@link QueryAttribution#INFERRED} — the backend log's client address is the
 * pooler, never the original tenant.</p>
 */
public final class PostgresJsonlogParser {

    private static final Pattern DURATION = Pattern.compile("duration:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z");

    private PostgresJsonlogParser() {
    }

    /** Result of parsing one jsonlog line: zero or one event. */
    public record Parsed(Optional<QueryAuditEvent> event, boolean malformed) {
    }

    /**
     * Parses one jsonlog object (minimal hand-rolled field extraction — the
     * classpath intentionally avoids a direct Jackson dependency; fields are
     * flat strings/numbers so a small extractor is sufficient).
     *
     * @param jsonLine     one raw jsonlog line
     * @param pooled       true when the downstream is a PgBouncer leg
     * @param ingressIp    bridge ingress client IP (authoritative at ingress)
     * @param ingressUser  bridge ingress username, may be null
     * @param ingressDb    bridge ingress database, may be null
     */
    public static Parsed parseLine(String jsonLine, boolean pooled,
                                   String ingressIp, String ingressUser, String ingressDb) {
        if (jsonLine == null || jsonLine.isBlank()) {
            return new Parsed(Optional.empty(), true);
        }
        String line = jsonLine.trim();
        if (!line.startsWith("{") || !line.endsWith("}")) {
            return new Parsed(Optional.empty(), true);
        }
        String severity = field(line, "error_severity");
        String message = field(line, "message");
        String statement = field(line, "statement");
        if (message == null) {
            return new Parsed(Optional.empty(), true);
        }
        boolean isError = "ERROR".equalsIgnoreCase(severity) || "FATAL".equalsIgnoreCase(severity);
        if (message.startsWith("statement: ") && !isError) {
            return statementEvent(line, message.substring("statement: ".length()), null, null,
                    pooled, ingressIp, ingressUser, ingressDb);
        }
        Matcher dm = DURATION.matcher(message);
        if (dm.find() && !isError) {
            return statementEvent(line, null, parseMillis(dm.group(1)), null,
                    pooled, ingressIp, ingressUser, ingressDb);
        }
        if (isError) {
            String sql = statement != null ? statement : null;
            return statementEvent(line, sql, null, field(line, "state_code"),
                    pooled, ingressIp, ingressUser, ingressDb);
        }
        return new Parsed(Optional.empty(), false);
    }

    private static Parsed statementEvent(String line, String sql, Double durationMs, String errorCode,
                                         boolean pooled, String ingressIp, String ingressUser, String ingressDb) {
        String user = field(line, "user");
        String db = field(line, "dbname");
        String remoteHost = field(line, "remote_host");
        String remotePort = field(line, "remote_port");
        String sessionId = field(line, "session_id");
        String pid = field(line, "pid");
        String ts = field(line, "timestamp");
        QueryAuditEvent e = new QueryAuditEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setObservedAt(parseTs(ts));
        e.setEngine(DatabaseEngineType.POSTGRES);
        e.setDatabase(db != null ? db : ingressDb);
        if (e.getDatabase() != null) {
            e.setManagedDatabaseId(DatabaseEngineType.POSTGRES.name() + ":" + e.getDatabase());
        }
        e.setProvisionedUser(user != null ? user : ingressUser);
        if (pooled) {
            e.setSourceIp(ingressIp);
            e.setSourcePort(null);
            e.setAttribution(QueryAttribution.INFERRED);
            e.setAuditConfidence(AuditConfidence.MEDIUM);
        } else {
            e.setSourceIp(ingressIp != null ? ingressIp : ("[local]".equals(remoteHost) ? "local" : remoteHost));
            e.setSourcePort(parsePort(remotePort));
            e.setAttribution(ingressIp != null ? QueryAttribution.AUTHORITATIVE : QueryAttribution.INFERRED);
            e.setAuditConfidence(ingressIp != null ? AuditConfidence.HIGH : AuditConfidence.MEDIUM);
        }
        String normalized = sql == null ? "?" : QueryShapeRedactor.normalize(sql);
        e.setNormalizedShape(cap(normalized));
        e.setShapeHash(QueryShapeRedactor.shapeHash(e.getNormalizedShape()));
        String cmd = QueryShapeRedactor.commandType(e.getNormalizedShape());
        e.setCommandType(cmd);
        e.setOperationClass(QueryShapeRedactor.operationClass(cmd));
        e.setDurationMs(durationMs == null ? null : Math.round(durationMs));
        if (errorCode != null) {
            e.setSuccess(false);
            e.setErrorCode(errorCode);
            e.setErrorClass("sqlstate-" + errorCode);
        } else {
            e.setSuccess(null);
        }
        e.setObservationSource(ObservationSource.POSTGRES_JSONLOG);
        e.setSessionId(sessionId);
        e.setConnectionId(pid);
        return new Parsed(Optional.of(e), false);
    }

    private static String cap(String s) {
        return s.length() > QueryShapeRedactor.MAX_NORMALIZED_LENGTH
                ? s.substring(0, QueryShapeRedactor.MAX_NORMALIZED_LENGTH) : s;
    }

    static Double parseMillis(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer parsePort(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int p = Integer.parseInt(raw.trim());
            return p > 0 && p <= 65535 ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Instant parseTs(String raw) {
        if (raw == null) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(raw.trim(), TS).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    /**
     * Minimal flat JSON string/number field extractor (no nesting needed).
     * Public for the collector's session-correlation pre-pass (extracts only
     * {@code user}/{@code dbname} identity, never statement text).
     */
    public static String field(String json, String name) {
        String key = "\"" + name + "\"";
        int ki = json.indexOf(key);
        if (ki < 0) {
            return null;
        }
        int ci = json.indexOf(':', ki + key.length());
        if (ci < 0) {
            return null;
        }
        int i = ci + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        char c = json.charAt(i);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            boolean esc = false;
            for (int j = i + 1; j < json.length(); j++) {
                char k = json.charAt(j);
                if (esc) {
                    sb.append(k == 'n' ? '\n' : k == 't' ? '\t' : k == 'r' ? '\r' : k);
                    esc = false;
                    continue;
                }
                if (k == '\\') {
                    esc = true;
                    continue;
                }
                if (k == '"') {
                    return sb.toString();
                }
                sb.append(k);
            }
            return null;
        }
        int j = i;
        while (j < json.length() && json.charAt(j) != ',' && json.charAt(j) != '}') {
            j++;
        }
        String raw = json.substring(i, j).trim();
        return raw.isEmpty() || "null".equals(raw) ? null : raw;
    }
}
