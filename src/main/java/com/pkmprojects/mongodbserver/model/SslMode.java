package com.pkmprojects.mongodbserver.model;

/**
 * TLS mode for externally issued PostgreSQL endpoints.
 * Postgres-only; Mongo/MySQL use a boolean TLS toggle elsewhere.
 */
public enum SslMode {
    DISABLE,
    REQUIRE,
    VERIFY_FULL;

    public static SslMode parse(String raw, SslMode fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return switch (raw.trim().toLowerCase()) {
            case "disable" -> DISABLE;
            case "verify-full", "verify_full", "verifyfull" -> VERIFY_FULL;
            default -> REQUIRE;
        };
    }

    public String wireValue() {
        return switch (this) {
            case DISABLE -> "disable";
            case VERIFY_FULL -> "verify-full";
            case REQUIRE -> "require";
        };
    }
}
