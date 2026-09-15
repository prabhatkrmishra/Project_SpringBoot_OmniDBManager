package com.pkmprojects.mongodbserver.audit.collector;

import com.pkmprojects.mongodbserver.audit.ingest.BridgeSessionEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumes bridge container log lines carrying {@code "audit":
 * "bridge-session-start"} / {@code "bridge-session-end"} JSON payloads.
 *
 * <p>The Docker json-file driver wraps container stderr in
 * {@code {"log":"...","stream":"stderr","time":"..."} envelopes}; both the
 * envelope form and raw JSON lines are accepted. Only identity fields are
 * extracted (session id, client IP/port, user, database, mode, profile,
 * route, timestamps). Anything else on the line is ignored; malformed lines
 * are skipped, never thrown.</p>
 */
public final class BridgeSessionLogTailer {

    private static final Pattern STR = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern NUM = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*(-?\\d+)");

    private BridgeSessionLogTailer() {
    }

    public record Parsed(Optional<BridgeSessionEvent> session, boolean isEnd, String sessionId) {
    }

    /** Parses one container-log line (envelope or raw). Never throws. */
    public static Parsed parseLine(String line) {
        if (line == null || line.isBlank() || !line.contains("bridge-session-")) {
            return new Parsed(Optional.empty(), false, null);
        }
        String payload = line;
        // Unwrap {"log":"...","stream":...} envelope when present.
        int logIdx = line.indexOf("\"log\"");
        if (logIdx >= 0) {
            Matcher m = Pattern.compile("\"log\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(line);
            if (m.find()) {
                payload = unescape(m.group(1));
            }
        }
        boolean isEnd = payload.contains("bridge-session-end");
        boolean isStart = payload.contains("bridge-session-start");
        if (!isStart && !isEnd) {
            return new Parsed(Optional.empty(), false, null);
        }
        String sessionId = str(payload, "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            return new Parsed(Optional.empty(), isEnd, null);
        }
        if (isEnd) {
            return new Parsed(Optional.empty(), true, sessionId);
        }
        try {
            BridgeSessionEvent session = new BridgeSessionEvent(
                    parseInstant(str(payload, "at")),
                    sessionId,
                    emptyToNull(str(payload, "client_ip")),
                    num(str(payload, "client_port"), num(payload, "client_port")),
                    emptyToNull(str(payload, "user")),
                    emptyToNull(str(payload, "database")),
                    emptyToNull(str(payload, "mode")),
                    emptyToNull(str(payload, "profile")),
                    emptyToNull(str(payload, "route")),
                    null,
                    parseInstant(str(payload, "at")),
                    null);
            return new Parsed(Optional.of(session), false, sessionId);
        } catch (IllegalArgumentException e) {
            return new Parsed(Optional.empty(), false, sessionId);
        }
    }

    /** Feeds lines into session/end consumers. */
    public static void feed(String line, Consumer<BridgeSessionEvent> starts, Consumer<String> ends) {
        Parsed p = parseLine(line);
        p.session().ifPresent(starts);
        if (p.isEnd() && p.sessionId() != null) {
            ends.accept(p.sessionId());
        }
    }

    private static String str(String json, String name) {
        Matcher m = STR.matcher(json);
        while (m.find()) {
            if (name.equals(m.group(1))) {
                return unescape(m.group(2));
            }
        }
        return null;
    }

    private static Integer num(String s, Long n) {
        if (n != null) {
            return n > 0 && n <= 65535 ? n.intValue() : null;
        }
        if (s == null) {
            return null;
        }
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p <= 65535 ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long num(String json, String name) {
        Matcher m = NUM.matcher(json);
        while (m.find()) {
            if (name.equals(m.group(1))) {
                try {
                    return Long.parseLong(m.group(2));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                out.append(c == 'n' ? '\n' : c == 't' ? '\t' : c == 'r' ? '\r' : c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
