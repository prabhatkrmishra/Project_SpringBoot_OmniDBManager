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
import java.util.Set;
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

    /**
     * Result of parsing one jsonlog line: zero or one event, plus an optional
     * duration sibling to join against a pending statement. The parser stays
     * pure and stateless — correlation state lives in the collector.
     */
    public record Parsed(Optional<QueryAuditEvent> event, Optional<DurationSibling> duration,
            boolean malformed) {
        public Parsed(Optional<QueryAuditEvent> event, boolean malformed) {
            this(event, Optional.empty(), malformed);
        }
    }

    /**
     * A bare {@code duration:} sibling line: no statement of its own, only a
     * timing to join against a pending statement. Carries the backend
     * identity key ({@code pid}, {@code session_id}), the observed bridge
     * SID (informational — IP comes from the pending statement, never from
     * a re-lookup at duration time), and the duration.
     */
    public record DurationSibling(String pid, String sessionId, long durationMs, String bridgeSid) {
        public DurationSibling(String pid, String sessionId, long durationMs) {
            this(pid, sessionId, durationMs, null);
        }
    }

    /** Downstream tenant marker prefix set by the bridge ({@code omnidb:<sid>}). */
    public static final String BRIDGE_APP_PREFIX = "omnidb:";

    /**
     * Extracts the bridge correlation SID from a PG {@code application_name}
     * value. Returns the SID for {@code omnidb:<sid>} with a well-formed
     * {@code [a-z0-9]{8,32}} suffix, otherwise null. Bare {@code omnidb}
     * (pre-SID clients), spoofed/oversized values, and nulls yield null —
     * callers must treat null as uncorrelated (sourceIp=null), never fall
     * back to remote_host or latest-session.
     */
    public static String extractBridgeSid(String applicationName) {
        if (applicationName == null) {
            return null;
        }
        String v = applicationName.trim();
        if (!v.startsWith(BRIDGE_APP_PREFIX)) {
            return null;
        }
        String sid = v.substring(BRIDGE_APP_PREFIX.length());
        if (sid.length() < 8 || sid.length() > 32) {
            return null;
        }
        for (int i = 0; i < sid.length(); i++) {
            char c = sid.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                continue;
            }
            return null;
        }
        return sid;
    }

    /**
     * True for tenant-issued application names: bare {@code omnidb}
     * (pre-SID clients) or {@code omnidb:<valid-sid>}. Anything else
     * (including manager JDBC/Adminer defaults) is not tenant-marked.
     */
    public static boolean isTenantAppName(String applicationName) {
        if ("omnidb".equals(applicationName)) {
            return true;
        }
        return extractBridgeSid(applicationName) != null;
    }

    /**
     * Parses one jsonlog object (minimal hand-rolled field extraction — the
     * classpath intentionally avoids a direct Jackson dependency; fields are
     * flat strings/numbers so a small extractor is sufficient).
     *
      * @param jsonLine     one raw jsonlog line
     * @param pooled       true when the resolved bridge leg is pooled
     *                     (INFERRED); false for direct (AUTHORITATIVE when IP present)
     * @param ingressIp    bridge ingress client IP for the EXACT resolved
     *                     SID, or null when uncorrelated. Never a pooler/
     *                     bridge/remote_host address.
     * @param ingressUser  bridge ingress username, may be null
     * @param ingressDb    bridge ingress database, may be null
      */
    public static Parsed parseLine(String jsonLine, boolean pooled,
                                   String ingressIp, String ingressUser, String ingressDb) {
        return parseLine(jsonLine, pooled, ingressIp, ingressUser, ingressDb, null);
    }

    /**
     * SID-aware parse. {@code bridgeSid} is the EXACT resolved bridge SID
     * for this line (from the collector's SID lookup), or null when the
     * line's SID is missing/unknown. IP is assigned from
     * {@code ingressIp} only (sanitized); when null/invalid the event gets
     * {@code sourceIp=null, INFERRED/MEDIUM} — never remote_host, never
     * latest-session, never hostname.
     */
    public static Parsed parseLine(String jsonLine, boolean pooled,
                                   String ingressIp, String ingressUser, String ingressDb,
                                   String bridgeSid) {
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
                    pooled, ingressIp, ingressUser, ingressDb, bridgeSid);
        }
        Matcher dm = DURATION.matcher(message);
        if (dm.find() && !isError) {
            // Bare duration sibling with no statement text: never an event of
            // its own. The correlator joins it to the pending statement with
            // the same backend identity, or drops it when unmatched.
            Double ms = parseMillis(dm.group(1));
            if (ms == null) {
                return new Parsed(Optional.empty(), false);
            }
            return new Parsed(Optional.empty(),
                    Optional.of(new DurationSibling(
                            field(line, "pid"), field(line, "session_id"), Math.round(ms),
                            extractBridgeSid(field(line, "application_name")))),
                    false);
        }
        if (isError) {
            String sql = statement != null ? statement : null;
            return statementEvent(line, sql, null, field(line, "state_code"),
                    pooled, ingressIp, ingressUser, ingressDb, bridgeSid);
        }
        return new Parsed(Optional.empty(), false);
    }

    /**
     * Control-plane identities whose statements are manager surface, never
     * tenant activity: the PostgreSQL superuser used by the manager's own
     * JDBC pool ({@code POSTGRES_ROOT_USER}, default {@code root}) and by
     * Adminer SSO, plus the pooler's internal auth roles. A control-plane
     * username is dropped ONLY when the line lacks the tenant-issued
     * application marker (bare {@code omnidb} or {@code omnidb:<sid>}), so
     * a genuine tenant provisioned as {@code root/postgres} over a bridged
     * tenant string remains auditable.
      */
    private static final Set<String> CONTROL_PLANE_USERS =
            Set.of("root", "postgres", "pgbouncer_auth", "pgbouncer_stats");

    /**
     * Whether a jsonlog line is manager control-plane surface rather than
     * tenant activity: a control-plane user connecting without the
     * tenant-issued application marker. Bridge-issued tenant strings always
     * pin {@code omnidb} or {@code omnidb:<sid>} downstream, so this never
     * drops real tenant traffic (including a tenant named like a superuser).
      */
    static boolean isControlPlaneSurface(String user, String applicationName) {
        return user != null && CONTROL_PLANE_USERS.contains(user) && !isTenantAppName(applicationName);
    }

    private static Parsed statementEvent(String line, String sql, Double durationMs, String errorCode,
                                         boolean pooled, String ingressIp, String ingressUser, String ingressDb,
                                         String bridgeSid) {
        String user = field(line, "user");
        // Manager JDBC pool, Adminer SSO sessions, and the pooler's internal
        // auth roles are infrastructure surface, never tenant activity —
        // drop before building an event.
        String applicationName = field(line, "application_name");
        if (isControlPlaneSurface(user, applicationName)) {
            return new Parsed(Optional.empty(), false);
        }
        // Observed SID from the log line (may be null for pre-SID clients
        // or non-bridged traffic). The collector resolves the EXACT bridge
        // session from this value; the parser never guesses from user/db.
        String observedSid = extractBridgeSid(applicationName);
        String effectiveSid = bridgeSid != null ? bridgeSid : observedSid;
        String db = field(line, "dbname");
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
        // Exact-IP rule: sanitized bridge-ingress IP only. Null when
        // uncorrelated/invalid — never remote_host (pooler/bridge egress),
        // never latest-session, never hostname, never loopback.
        String safeIp = SourceIpRules.sanitizeTenantIp(ingressIp);
        e.setSourceIp(safeIp);
        e.setSourcePort(null);
        if (safeIp != null && !pooled) {
            e.setAttribution(QueryAttribution.AUTHORITATIVE);
            e.setAuditConfidence(AuditConfidence.HIGH);
        } else {
            e.setAttribution(QueryAttribution.INFERRED);
            e.setAuditConfidence(AuditConfidence.MEDIUM);
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
        e.setBridgeSessionId(effectiveSid);
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
