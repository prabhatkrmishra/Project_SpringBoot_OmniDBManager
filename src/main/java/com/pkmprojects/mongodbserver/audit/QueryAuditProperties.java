package com.pkmprojects.mongodbserver.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-tunable query-audit behavior, bound from {@code app.query-audit.*}.
 * Collection stays bounded via the TTL index (days), the normalized-shape cap,
 * and explicit enable flags per telemetry source.
 */
@ConfigurationProperties(prefix = "app.query-audit")
public record QueryAuditProperties(
        boolean enabled,
        int retentionDays,
        int maxNormalizedLength,
        boolean postgresEnabled,
        boolean mysqlEnabled,
        boolean mongoEnabled,
        String pgJsonlog,
        String mysqlSlowlog,
        String bridgeLog) {

    /**
     * Test-only factory with default tail paths. A static factory (not a
     * constructor overload): Spring Boot applies record constructor binding
     * only with a single canonical constructor, so a second constructor
     * would silently break application startup ("No default constructor
     * found"). Never used by runtime configuration binding.
     */
    public static QueryAuditProperties forTests(boolean enabled, int retentionDays, int maxNormalizedLength,
                                                boolean postgresEnabled, boolean mysqlEnabled, boolean mongoEnabled) {
        return new QueryAuditProperties(enabled, retentionDays, maxNormalizedLength,
                postgresEnabled, mysqlEnabled, mongoEnabled,
                "/var/lib/postgresql/log/postgresql.json", "/var/lib/mysql/slow.log", "/var/log/omnidb/bridge.log");
    }

    /** Filesystem path of the PostgreSQL jsonlog tail source. */
    public String effectivePgJsonlog() {
        return pgJsonlog == null || pgJsonlog.isBlank()
                ? "/var/lib/postgresql/log/postgresql.json" : pgJsonlog.trim();
    }

    /** Filesystem path of the MySQL slow-log tail source. */
    public String effectiveMysqlSlowlog() {
        return mysqlSlowlog == null || mysqlSlowlog.isBlank()
                ? "/var/lib/mysql/slow.log" : mysqlSlowlog.trim();
    }

    /** Filesystem path of the bridge container-log tail source. */
    public String effectiveBridgeLog() {
        return bridgeLog == null || bridgeLog.isBlank()
                ? "/var/log/omnidb/bridge.log" : bridgeLog.trim();
    }

    public QueryAuditProperties {
        if (retentionDays <= 0) retentionDays = 30;
        if (maxNormalizedLength <= 0) maxNormalizedLength = QueryShapeRedactor.MAX_NORMALIZED_LENGTH;
    }

    /** Effective retention, clamped to 1..365 days. */
    public int effectiveRetentionDays() {
        return Math.max(1, Math.min(retentionDays, 365));
    }

    /** Effective normalized-shape cap, clamped to 256..8000 chars. */
    public int effectiveMaxNormalizedLength() {
        return Math.max(256, Math.min(maxNormalizedLength, 8000));
    }
}
