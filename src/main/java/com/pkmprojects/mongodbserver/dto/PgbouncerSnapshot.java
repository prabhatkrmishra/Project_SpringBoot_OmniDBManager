package com.pkmprojects.mongodbserver.dto;

import java.util.List;

/**
 * PgBouncer pooling snapshot — facet of Postgres engine monitoring.
 * When PgBouncer is disabled, {@code enabled=false} and other fields are null/empty;
 * UI hides the panel. Thresholds for degraded classification are configurable
 * via {@code app.pgbouncer.thresholds.*}.
 */
public record PgbouncerSnapshot(
        boolean enabled,
        String status,
        List<Pool> pools,
        Stats stats) {

    public static PgbouncerSnapshot disabled() {
        return new PgbouncerSnapshot(false, null, List.of(), null);
    }

    public record Pool(
            String database,
            String poolMode,
            int clientsActive,
            int clientsWaiting,
            int serversActive,
            int serversIdle,
            double maxWaitSeconds) {
    }

    public record Stats(
            long totalQueries,
            double avgQueryTimeMs,
            long totalWaitTimeMs) {
    }
}
