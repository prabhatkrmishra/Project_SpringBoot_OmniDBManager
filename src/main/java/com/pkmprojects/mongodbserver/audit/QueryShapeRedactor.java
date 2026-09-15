package com.pkmprojects.mongodbserver.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes SQL statements and Mongo command shapes into bounded,
 * redacted representations safe for persistence in {@code query_audit}.
 *
 * <p>Policy: normalized/redacted only. Literal values (strings, numbers,
 * query parameters, BSON values, URLs carrying credentials, tokens in
 * comments) are replaced with placeholders. The output is capped in length,
 * flattened to one line, and hashed with SHA-256 so equivalent shapes group
 * under one {@code shapeHash}.</p>
 *
 * <p>Never emits: passwords, auth material, SCRAM data, Authorization
 * headers, cookies, session secrets, connection strings, webhook secrets,
 * API keys, literal query values, arbitrary request bodies, or unbounded
 * free text.</p>
 */
public final class QueryShapeRedactor {

    /** Schema version of the canonical audit event. */
    public static final int SCHEMA_VERSION = 1;

    /** Hard cap on the normalized representation stored per event. */
    public static final int MAX_NORMALIZED_LENGTH = 2000;

    /** Maximum input length accepted before truncation. */
    public static final int MAX_INPUT_LENGTH = 65536;

    private static final Pattern SINGLE_QUOTED = Pattern.compile("'(?:[^'\\\\]|\\\\.|'')*'", Pattern.DOTALL);
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"", Pattern.DOTALL);
    private static final Pattern DOLLAR_QUOTED = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*\\$.*?\\$[A-Za-z_][A-Za-z0-9_]*\\$", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("(?<![A-Za-z0-9_$.])(?:0x[0-9a-fA-F]+|\\d+\\.\\d+|\\.\\d+|\\d+)(?![A-Za-z0-9_])");
    private static final Pattern JDBC_PARAM = Pattern.compile("\\?");
    private static final Pattern PG_PARAM = Pattern.compile("\\$\\d+");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)([^\\s/@\"']+)@");
    private static final Pattern CONN_KV_SECRET = Pattern.compile("(?i)\\b(password|passwd|pwd|secret|token|api_key|apikey|auth)\\b\\s*=\\s*[^\\s,;\"']+");

    private static final Set<String> COMMAND_VERBS = Set.of(
            "select", "insert", "update", "delete", "merge", "upsert", "replace",
            "create", "alter", "drop", "truncate", "grant", "revoke",
            "begin", "commit", "rollback", "savepoint", "release",
            "set", "show", "describe", "desc", "explain",
            "find", "aggregate", "count", "distinct",
            "insertone", "insertmany", "updateone", "updatemany",
            "deleteone", "deletemany", "findoneandupdate", "findoneanddelete",
            "createindex", "dropindex", "createcollection", "dropcollection");

    private QueryShapeRedactor() {
    }

    /**
     * Normalizes one SQL statement or Mongo command rendering into a bounded
     * redacted shape. Returns {@code "?"} for blank input.
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "?";
        }
        String s = input.length() > MAX_INPUT_LENGTH ? input.substring(0, MAX_INPUT_LENGTH) : input;
        s = scrubUrlCredentials(s);
        s = CONN_KV_SECRET.matcher(s).replaceAll("$1=***");
        s = BLOCK_COMMENT.matcher(s).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        s = DOLLAR_QUOTED.matcher(s).replaceAll("'?'");
        s = stripDollarQuoted(s);
        s = stripQuotedLiterals(s);
        s = NUMERIC_LITERAL.matcher(s).replaceAll("?");
        s = JDBC_PARAM.matcher(s).replaceAll("?");
        s = PG_PARAM.matcher(s).replaceAll("?");
        StringBuilder flat = new StringBuilder(Math.min(s.length(), MAX_NORMALIZED_LENGTH + 64));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c < 0x20) {
                flat.append(' ');
            } else {
                flat.append(c);
            }
        }
        s = WS.matcher(flat.toString().trim()).replaceAll(" ");
        s = collapseValuesTuples(s);
        // Collapse long placeholder runs (?, ?, ?) to a single marker.
        s = s.replaceAll("\\(\\s*(\\?\\s*,\\s*){2,}\\?\\s*\\)", "(?)");
        s = s.replaceAll("(\\?\\s*,\\s*){3,}\\?", "?");
        if (s.isEmpty()) {
            return "?";
        }
        if (s.length() > MAX_NORMALIZED_LENGTH) {
            s = s.substring(0, MAX_NORMALIZED_LENGTH);
        }
        return s;
    }

    /**
     * Renders a Mongo command document shape: operator/field names are kept,
     * every leaf value is replaced with {@code 1} (redacted), nesting is
     * capped at {@code maxDepth}, total output at {@link #MAX_NORMALIZED_LENGTH}.
     */
    public static String normalizeCommandShape(String commandJson, int maxDepth) {
        if (commandJson == null || commandJson.isBlank()) {
            return "?";
        }
        String s = commandJson.length() > MAX_INPUT_LENGTH ? commandJson.substring(0, MAX_INPUT_LENGTH) : commandJson;
        s = scrubUrlCredentials(s);
        s = stripQuotedLiteralsPreservingKeys(s);
        StringBuilder out = new StringBuilder(Math.min(s.length(), MAX_NORMALIZED_LENGTH + 64));
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int effectiveMax = Math.max(1, Math.min(maxDepth, 8));
        for (int i = 0; i < s.length() && out.length() < MAX_NORMALIZED_LENGTH; i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                    out.append('1');
                }
                continue;
            }
            if (c == '"') {
                int qj = i + 1;
                boolean qesc = false;
                while (qj < s.length()) {
                    char qk = s.charAt(qj);
                    if (qesc) {
                        qesc = false;
                        qj++;
                        continue;
                    }
                    if (qk == '\\') {
                        qesc = true;
                        qj++;
                        continue;
                    }
                    if (qk == '"') {
                        break;
                    }
                    qj++;
                }
                int qa = qj + 1;
                while (qa < s.length() && Character.isWhitespace(s.charAt(qa))) {
                    qa++;
                }
                if (qj < s.length() && qa < s.length() && s.charAt(qa) == ':') {
                    // Preserved key — emit verbatim including quotes and colon.
                    out.append(s, i, qa + 1);
                    i = qa;
                } else {
                    out.append('1');
                    i = qj < s.length() ? qj : s.length() - 1;
                }
                continue;
            }
            switch (c) {
                case '{', '[' -> {
                    depth++;
                    if (depth > effectiveMax) {
                        out.append("{...}");
                        // skip to matching close at this level
                        int skip = depth;
                        i++;
                        boolean ins = false;
                        boolean esc = false;
                        for (; i < s.length(); i++) {
                            char k = s.charAt(i);
                            if (esc) { esc = false; continue; }
                            if (ins) {
                                if (k == '\\') esc = true;
                                else if (k == '"') ins = false;
                                continue;
                            }
                            if (k == '"') ins = true;
                            else if (k == '{' || k == '[') skip++;
                            else if (k == '}' || k == ']') {
                                skip--;
                                if (skip < depth) break;
                            }
                        }
                        depth--;
                    } else {
                        out.append(c);
                    }
                }
                case '}', ']' -> {
                    depth = Math.max(0, depth - 1);
                    out.append(c);
                }
                case '\r', '\n', '\t' -> out.append(' ');
                default -> {
                    if (c < 0x20) {
                        out.append(' ');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        String shaped = WS.matcher(out.toString().trim()).replaceAll(" ");
        // Replace remaining bare literal values after ':' or ',' with 1.
        shaped = shaped.replaceAll("(:\\s*)(true|false|null|-?\\d[\\d.]*)", "$11");
        if (shaped.isEmpty()) {
            return "?";
        }
        return shaped.length() > MAX_NORMALIZED_LENGTH ? shaped.substring(0, MAX_NORMALIZED_LENGTH) : shaped;
    }

    /**
     * Strips PostgreSQL dollar-quoted string bodies, including anonymous
     * {@code $$...$$} and mismatched-tag inputs (fail-safe: any
     * {@code $tag$} opener without its closer still redacts to end of input
     * for that span). Tag names are never secret-bearing; bodies may be.
     */
    private static String stripDollarQuoted(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '$') {
                int j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                if (j < s.length() && s.charAt(j) == '$') {
                    String tag = s.substring(i, j + 1);
                    int close = s.indexOf(tag, j + 1);
                    out.append("'?'");
                    i = close < 0 ? s.length() : close + tag.length();
                    continue;
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * Canonicalizes multi-row {@code VALUES (...), (...), ...} tuple runs into
     * one deterministic shape: the first tuple is kept verbatim and every
     * subsequent tuple is dropped, so tuple count never changes the shape
     * hash. Runs after literal folding, so tuple bodies already consist of
     * {@code ?} placeholders, whitespace, commas and nested parentheses only.
     *
     * <p>Linear scan with paren-depth tracking: only the {@code VALUES}
     * keyword outside any nesting starts a run; unbalanced input is left
     * untouched (fail-safe). Single-tuple statements pass through
     * byte-identical.</p>
     */
    private static String collapseValuesTuples(String s) {
        int idx = indexOfValuesKeyword(s);
        if (idx < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        out.append(s, 0, idx + 6);
        int i = idx + 6;
        int n = s.length();
        boolean firstTupleKept = false;
        int droppedTuples = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '(') {
                int depth = 1;
                int j = i + 1;
                while (j < n && depth > 0) {
                    char k = s.charAt(j);
                    if (k == '(') depth++;
                    else if (k == ')') depth--;
                    j++;
                }
                if (depth != 0) {
                    // Unbalanced: fail safe, keep the remainder verbatim.
                    out.append(s, i, n);
                    return out.toString();
                }
                if (!firstTupleKept) {
                    out.append(s, i, j);
                    firstTupleKept = true;
                } else {
                    droppedTuples++;
                }
                i = j;
                // Peek past separators: if dropped tuples are followed by a
                // non-tuple continuation (e.g. RETURNING), keep exactly one
                // separator space so words don't fuse.
                if (droppedTuples > 0) {
                    // If another tuple follows, the run continues: no space.
                    // Otherwise keep exactly one separator space before a
                    // trailing continuation (e.g. RETURNING) so words fuse.
                    int t = i;
                    boolean runContinues = false;
                    while (t < n && (s.charAt(t) == ',' || Character.isWhitespace(s.charAt(t)))) {
                        if (s.charAt(t) == ',') {
                            int u = t + 1;
                            while (u < n && Character.isWhitespace(s.charAt(u))) u++;
                            if (u < n && s.charAt(u) == '(') {
                                runContinues = true;
                                break;
                            }
                        }
                        t++;
                    }
                    if (!runContinues && t < n && s.charAt(t) != ')' && s.charAt(t) != ';'
                            && s.charAt(t) != ',') {
                        out.append(' ');
                    }
                }
                // Skip separators between tuples: whitespace runs and commas
                // that are followed by another '('.
                while (i < n) {
                    char sep = s.charAt(i);
                    if (Character.isWhitespace(sep)) {
                        if (!firstTupleKept) {
                            out.append(sep);
                        }
                        i++;
                    } else if (sep == ',') {
                        int t = i + 1;
                        while (t < n && Character.isWhitespace(s.charAt(t))) t++;
                        if (t < n && s.charAt(t) == '(' && firstTupleKept) {
                            // Another tuple: drop it (skip comma, loop continues).
                            i++;
                        } else {
                            out.append(s, i, n);
                            return out.toString();
                        }
                    } else {
                        break;
                    }
                }
                continue;
            } else if (c == ',' || Character.isWhitespace(c)) {
                out.append(c);
                i++;
            } else {
                // Not a tuple run (DEFAULT, ROW expressions, CTE edge cases):
                // keep the remainder verbatim.
                out.append(s, i, n);
                return out.toString();
            }
        }
        return out.toString();
    }

    /**
     * Finds the {@code VALUES} keyword outside any parenthesis nesting,
     * case-insensitive, bounded by non-identifier characters. Returns the
     * start index or -1.
     */
    private static int indexOfValuesKeyword(String s) {
        int depth = 0;
        int n = s.length();
        for (int i = 0; i + 6 <= n; i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) depth--;
            } else if (depth == 0 && (c == 'V' || c == 'v')) {
                if (s.regionMatches(true, i, "VALUES", 0, 6)) {
                    boolean beforeOk = i == 0 || (!Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_');
                    int e = i + 6;
                    boolean afterOk = e >= n || (!Character.isLetterOrDigit(s.charAt(e)) && s.charAt(e) != '_');
                    if (beforeOk && afterOk) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Linear credential scrub for URLs of the form {@code scheme://cred@host}.
     * Emits {@code scheme://***@host} quoteless so the later quote-stripper
     * preserves the marker as proof the secret is gone.
     */
    private static String scrubUrlCredentials(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int schemeEnd = indexOfSchemeEnd(s, i);
            if (schemeEnd < 0) {
                out.append(s, i, s.length());
                break;
            }
            // find '@' before a terminator
            int at = -1;
            int j = schemeEnd;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '@') {
                    at = j;
                    break;
                }
                if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == ',' || c == ';' || c == ')') {
                    break;
                }
                j++;
            }
            if (at < 0) {
                out.append(s, i, schemeEnd);
                i = schemeEnd;
                continue;
            }
            // emit scheme://***@ ; skip original credentials
            out.append(s, i, schemeEnd);
            out.append("***@");
            i = at + 1;
        }
        return out.toString();
    }

    /** Returns the index just past "://" of the next URL, or -1. */
    private static int indexOfSchemeEnd(String s, int from) {
        for (int i = from; i + 2 < s.length(); i++) {
            if (s.charAt(i) == ':' && s.charAt(i + 1) == '/' && s.charAt(i + 2) == '/') {
                int k = i - 1;
                while (k >= from && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '+' || s.charAt(k) == '-' || s.charAt(k) == '.')) {
                    k--;
                }
                if (k + 1 < i) {
                    return i + 3;
                }
            }
        }
        return -1;
    }

    /**
     * Linear single/double-quoted literal stripper. Replaces each quoted span
     * with {@code '?'} except the {@code ***@} credential markers emitted by
     * {@link #scrubUrlCredentials}, which are quoteless by construction.
     */
    private static String stripQuotedLiterals(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                int j = i + 1;
                boolean closed = false;
                while (j < s.length()) {
                    char k = s.charAt(j);
                    if (k == '\\' && j + 1 < s.length()) {
                        j += 2;
                        continue;
                    }
                    if (k == q) {
                        if (q == '\'' && j + 1 < s.length() && s.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                    j++;
                }
                out.append("'?'");
                i = closed ? j + 1 : s.length();
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Quote-stripper that preserves JSON/document field names: a quoted span
     * immediately followed by {@code :} is a key and kept verbatim; every
     * other quoted span (a value) becomes {@code 1}. Single-quoted spans are
     * always values and become {@code 1}.
     */
    private static String stripQuotedLiteralsPreservingKeys(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                int j = i + 1;
                StringBuilder inner = new StringBuilder();
                boolean closed = false;
                while (j < s.length()) {
                    char k = s.charAt(j);
                    if (k == '\\' && j + 1 < s.length()) {
                        inner.append(k);
                        inner.append(s.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (k == q) {
                        if (q == '\'' && j + 1 < s.length() && s.charAt(j + 1) == '\'') {
                            inner.append("''");
                            j += 2;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                    inner.append(k);
                    j++;
                }
                if (!closed) {
                    out.append('1');
                    break;
                }
                int after = j + 1;
                while (after < s.length() && Character.isWhitespace(s.charAt(after))) {
                    after++;
                }
                if (q == '"' && after < s.length() && s.charAt(after) == ':') {
                    out.append('"').append(inner).append('"');
                } else {
                    out.append('1');
                }
                i = j + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** SHA-256 hex of the normalized shape (UTF-8). */
    public static String shapeHash(String normalizedShape) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalizedShape.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Classifies the leading command verb of a normalized shape, lower-cased.
     * Returns {@code "other"} when no known verb leads the statement.
     */
    public static String commandType(String normalizedShape) {
        if (normalizedShape == null || normalizedShape.isBlank()) {
            return "other";
        }
        String s = normalizedShape.trim();
        if (s.startsWith("{")) {
            Matcher m = Pattern.compile("\\{\\s*\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*:").matcher(s);
            if (m.find()) {
                String verb = m.group(1).toLowerCase(Locale.ROOT);
                return COMMAND_VERBS.contains(verb) ? verb : "other";
            }
            return "other";
        }
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '(' || s.charAt(i) == ' ')) {
            i++;
        }
        int j = i;
        while (j < s.length() && Character.isLetter(s.charAt(j))) {
            j++;
        }
        String verb = s.substring(i, j).toLowerCase(Locale.ROOT);
        return COMMAND_VERBS.contains(verb) ? verb : "other";
    }

    /**
     * Broad operation class for operator filtering: read, write, ddl, txn,
     * admin, or other.
     */
    public static String operationClass(String commandType) {
        return switch (commandType == null ? "" : commandType.toLowerCase(Locale.ROOT)) {
            case "select", "show", "describe", "desc", "explain", "find", "aggregate",
                    "count", "distinct" -> "read";
            case "insert", "update", "delete", "merge", "upsert", "replace",
                    "insertone", "insertmany", "updateone", "updatemany",
                    "deleteone", "deletemany", "findoneandupdate",
                    "findoneanddelete" -> "write";
            case "create", "alter", "drop", "truncate", "grant", "revoke",
                    "createindex", "dropindex", "createcollection",
                    "dropcollection" -> "ddl";
            case "begin", "commit", "rollback", "savepoint", "release", "set" -> "txn";
            default -> "other";
        };
    }
}
