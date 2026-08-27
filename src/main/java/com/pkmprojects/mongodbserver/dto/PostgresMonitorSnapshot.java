package com.pkmprojects.mongodbserver.dto;

import java.time.Instant;

/**
 * Live snapshot of PostgreSQL server activity for the monitor page.
 */
public record PostgresMonitorSnapshot(
        boolean reachable,
        Instant measuredAt,
        String version,
        Long uptimeSeconds,
        Integer connectionCount,
        int databaseCount,
        Long totalStorageBytes,
        Integer activeConnections,
        Integer idleConnections,
        Long transactionsCommitted,
        Long transactionsRolledBack) {
}
