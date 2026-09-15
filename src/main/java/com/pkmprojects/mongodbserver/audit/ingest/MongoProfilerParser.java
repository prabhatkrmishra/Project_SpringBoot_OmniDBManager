package com.pkmprojects.mongodbserver.audit.ingest;

import com.pkmprojects.mongodbserver.audit.AuditConfidence;
import com.pkmprojects.mongodbserver.audit.ObservationSource;
import com.pkmprojects.mongodbserver.audit.QueryAttribution;
import com.pkmprojects.mongodbserver.audit.QueryAuditEvent;
import com.pkmprojects.mongodbserver.audit.QueryShapeRedactor;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;

/**
 * Converts MongoDB Community profiler ({@code system.profile}) documents into
 * canonical audit events.
 *
 * <p>Proven spike shape (mongo:8): {@code op, ns, command, millis, ts,
 * client, user ('name@db'), nreturned/nMatched/nModified/nUpserted,
 * queryShapeHash, planSummary}. The {@code user} and {@code client} fields
 * are database-native and authoritative. The raw {@code command} document is
 * never persisted — only a redacted field-name shape plus its hash.</p>
 *
 * <p>System/internal databases ({@code admin, local, config}) are excluded by
 * the caller; this parser additionally refuses them defensively.</p>
 */
public final class MongoProfilerParser {

    private static final Set<String> SYSTEM_DBS = Set.of("admin", "local", "config");

    private MongoProfilerParser() {
    }

    public record Parsed(Optional<QueryAuditEvent> event, boolean skipped) {
    }

    /** Converts one profiler document; system DBs yield empty/skipped. */
    @SuppressWarnings("unchecked")
    public static Parsed parse(Document doc) {
        if (doc == null) {
            return new Parsed(Optional.empty(), true);
        }
        String ns = doc.getString("ns");
        String db = ns != null && ns.contains(".") ? ns.substring(0, ns.indexOf('.')) : null;
        if (db == null || db.isBlank() || SYSTEM_DBS.contains(db)) {
            return new Parsed(Optional.empty(), true);
        }
        // Manager control-plane surface (own driver as root, admin tooling):
        // never tenant activity. Tenant users are provisioned per-database;
        // see the PG parser for the full rationale.
        String rawUser = userOf(doc.getString("user"));
        if (rawUser != null && (rawUser.equals("root") || rawUser.equals("admin"))) {
            return new Parsed(Optional.empty(), true);
        }
        String op = doc.getString("op");
        Object cmdObj = doc.get("command");
        String commandType = opToCommand(op, cmdObj);
        String shape = shapeOf(cmdObj, op, ns);
        QueryAuditEvent e = new QueryAuditEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setObservedAt(tsOf(doc.get("ts")));
        e.setEngine(DatabaseEngineType.MONGO);
        e.setDatabase(db);
        e.setManagedDatabaseId(DatabaseEngineType.MONGO.name() + ":" + db);
        e.setProvisionedUser(userOf(doc.getString("user")));
        e.setSourceIp(doc.getString("client"));
        e.setSourcePort(null);
        e.setOperationClass(QueryShapeRedactor.operationClass(commandType));
        e.setCommandType(commandType);
        e.setNormalizedShape(cap(shape));
        e.setShapeHash(QueryShapeRedactor.shapeHash(e.getNormalizedShape()));
        e.setDurationMs(longOf(doc.get("millis")));
        Long nreturned = longOf(doc.get("nreturned"));
        e.setRowsReturned(nreturned);
        e.setRowsAffected(affectedOf(doc));
        e.setSuccess(null);
        e.setObservationSource(ObservationSource.MONGO_PROFILER);
        e.setAttribution(QueryAttribution.AUTHORITATIVE);
        e.setAuditConfidence(AuditConfidence.HIGH);
        e.setSessionId(doc.get("queryShapeHash") instanceof String s ? s : null);
        Object opid = doc.get("opid");
        e.setConnectionId(opid == null ? null : String.valueOf(opid));
        return new Parsed(Optional.of(e), false);
    }

    private static String opToCommand(String op, Object cmdObj) {
        if (cmdObj instanceof Document cmd && !cmd.isEmpty()) {
            String first = cmd.keySet().iterator().next();
            if (first != null && !first.startsWith("$") && !"lsid".equals(first) && !"$db".equals(first)) {
                String verb = first.toLowerCase();
                if (!verb.isBlank()) {
                    return verb;
                }
            }
            for (String k : cmd.keySet()) {
                if (!k.startsWith("$") && !"lsid".equals(k) && !"$db".equals(k)) {
                    return k.toLowerCase();
                }
            }
        }
        if (op == null) {
            return "other";
        }
        return switch (op.toLowerCase()) {
            case "query" -> "find";
            case "insert" -> "insert";
            case "update" -> "update";
            case "remove" -> "delete";
            case "command" -> "other";
            default -> "other";
        };
    }

    private static String shapeOf(Object cmdObj, String op, String ns) {
        String collection = ns != null && ns.contains(".") ? ns.substring(ns.indexOf('.') + 1) : "?";
        if (cmdObj instanceof Document cmd) {
            String rendered = renderShape(cmd, 0, 4);
            return "{" + opName(op, cmd) + ": '" + collection + "', " + rendered + "}";
        }
        return (op == null ? "other" : op) + " '" + collection + "'";
    }

    private static String opName(String op, Document cmd) {
        for (String k : cmd.keySet()) {
            if (!k.startsWith("$") && !"lsid".equals(k) && !"$db".equals(k)) {
                return k;
            }
        }
        return op == null ? "command" : op;
    }

    @SuppressWarnings("unchecked")
    private static String renderShape(Object value, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return "{...}";
        }
        if (value instanceof Document doc) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> en : doc.entrySet()) {
                String k = en.getKey();
                if ("lsid".equals(k) || "$db".equals(k)) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append('"').append(k).append("\": ");
                Object v = en.getValue();
                if (v instanceof Document || v instanceof List || v instanceof Map) {
                    sb.append(renderShape(v, depth + 1, maxDepth));
                } else {
                    sb.append('1');
                }
                if (sb.length() > QueryShapeRedactor.MAX_NORMALIZED_LENGTH) {
                    break;
                }
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                if (item instanceof Document || item instanceof List || item instanceof Map) {
                    sb.append(renderShape(item, depth + 1, maxDepth));
                } else {
                    sb.append('1');
                }
                if (sb.length() > 512) {
                    sb.append(", ...");
                    break;
                }
            }
            sb.append(']');
            return sb.toString();
        }
        return "1";
    }

    private static String userOf(String user) {
        if (user == null || user.isBlank()) {
            return null;
        }
        int at = user.indexOf('@');
        return at > 0 ? user.substring(0, at) : user;
    }

    private static Instant tsOf(Object ts) {
        if (ts instanceof Date d) {
            return d.toInstant();
        }
        return Instant.now();
    }

    private static Long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static Long affectedOf(Document doc) {
        Long modified = longOf(doc.get("nModified"));
        Long matched = longOf(doc.get("nMatched"));
        Long upserted = longOf(doc.get("nUpserted"));
        Long inserted = longOf(doc.get("nInserted"));
        long total = (modified == null ? 0 : modified)
                + (upserted == null ? 0 : upserted)
                + (inserted == null ? 0 : inserted);
        if (total > 0) {
            return total;
        }
        return matched;
    }

    private static String cap(String s) {
        if (s == null || s.isBlank()) {
            return "?";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return flat.length() > QueryShapeRedactor.MAX_NORMALIZED_LENGTH
                ? flat.substring(0, QueryShapeRedactor.MAX_NORMALIZED_LENGTH) : flat;
    }
}
