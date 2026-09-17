package com.pkmprojects.mongodbserver.audit.ingest;

import java.util.regex.Pattern;

/**
 * Single documented rule set for tenant {@code sourceIp} handling across
 * PostgreSQL, MySQL, and MongoDB audit paths.
 *
 * <p>Rules (in order):</p>
 * <ol>
 *   <li>Null/blank to null (uncorrelated, never guess).</li>
 *   <li>Strip a single trailing {@code :port} / {@code [ip]:port} wrapper
 *       where unambiguous; never DNS-resolve.</li>
 *   <li>Accept only IP literals (IPv4 dotted-quad or IPv6 with {@code :}).
 *       Hostnames to null (never persist a hostname as {@code sourceIp},
 *       never DNS-resolve to manufacture an IP).</li>
 *   <li>Reject loopback ({@code 127.0.0.1}, {@code ::1},
 *       {@code ::ffff:127.0.0.1}, {@code localhost}) to null. Loopback is
 *       server-side infrastructure, never the tenant client.</li>
 *   <li>Private addresses are allowed: a private address may genuinely be
 *       the client in a private deployment. Infrastructure is distinguished
 *       by source (PostgreSQL never falls back to {@code remote_host};
 *       manager traffic is excluded by identity), not by IP range.</li>
 *   <li>No network calls, no reputation/geo lookup, no DNS.</li>
 * </ol>
 */
public final class SourceIpRules {

    private static final Pattern IPV4 = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
                    + "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    private SourceIpRules() {
    }

    /**
     * Returns a safe tenant {@code sourceIp} or null when unavailable.
     * Never throws, never resolves, never returns a hostname or loopback.
     */
    public static String sanitizeTenantIp(String candidate) {
        String ip = bareIp(candidate);
        if (ip == null) {
            return null;
        }
        if (!isIpLiteral(ip)) {
            return null;
        }
        if (isLoopback(ip)) {
            return null;
        }
        return ip;
    }

    /** True for IPv4 literals or IPv6 literals (contains {@code :}). */
    public static boolean isIpLiteral(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        String v = s.trim();
        if (IPV4.matcher(v).matches()) {
            return true;
        }
        return isIpv6Literal(v);
    }

    /** Minimal IPv6-literal check (no DNS, no resolution). */
    static boolean isIpv6Literal(String v) {
        if (!v.contains(":")) {
            return false;
        }
        int pct = v.indexOf('%');
        String addr = pct >= 0 ? v.substring(0, pct) : v;
        if (addr.isBlank() || addr.length() > 45) {
            return false;
        }
        int colons = 0;
        int dots = 0;
        for (int i = 0; i < addr.length(); i++) {
            char c = addr.charAt(i);
            if (c == ':') {
                colons++;
            } else if (c == '.') {
                dots++;
            } else if ((c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')
                    || c == '[' || c == ']') {
                continue;
            } else {
                return false;
            }
        }
        if (colons < 2) {
            return false;
        }
        if (addr.contains("[") || addr.contains("]")) {
            if (!(addr.startsWith("[") && addr.indexOf(']') == addr.length() - 1)) {
                return false;
            }
        }
        return dots <= 3;
    }

    /** True for loopback identities that must never be tenant sourceIp. */
    public static boolean isLoopback(String ip) {
        if (ip == null) {
            return false;
        }
        String v = ip.trim().toLowerCase();
        if (v.startsWith("[") && v.endsWith("]") && v.length() > 2) {
            v = v.substring(1, v.length() - 1);
        }
        return v.equals("127.0.0.1")
                || v.equals("::1")
                || v.equals("::ffff:127.0.0.1")
                || v.equals("localhost");
    }

    /**
     * Extracts a bare IP from {@code ip}, {@code ip:port},
     * {@code [ip]}, or {@code [ip]:port}. Returns null when no
     * unambiguous bare value exists. Never resolves hostnames.
     */
    static String bareIp(String candidate) {
        if (candidate == null) {
            return null;
        }
        String v = candidate.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.startsWith("[")) {
            int close = v.indexOf(']');
            if (close <= 1) {
                return null;
            }
            String inner = v.substring(1, close).trim();
            String rest = v.substring(close + 1).trim();
            if (!rest.isEmpty() && !(rest.startsWith(":") && rest.length() > 1)) {
                return null;
            }
            return inner.isEmpty() ? null : inner;
        }
        int colons = 0;
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) == ':') {
                colons++;
            }
        }
        if (colons >= 2) {
            return v;
        }
        int colon = v.lastIndexOf(':');
        if (colon >= 0) {
            String left = v.substring(0, colon).trim();
            String right = v.substring(colon + 1).trim();
            if (left.isEmpty() || right.isEmpty()) {
                return null;
            }
            boolean rightNumeric = true;
            for (int i = 0; i < right.length(); i++) {
                if (!Character.isDigit(right.charAt(i))) {
                    rightNumeric = false;
                    break;
                }
            }
            if (rightNumeric && IPV4.matcher(left).matches()) {
                return left;
            }
            return v;
        }
        return v;
    }
}
