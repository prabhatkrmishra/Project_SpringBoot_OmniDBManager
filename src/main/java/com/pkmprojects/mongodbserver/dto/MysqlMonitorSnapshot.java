package com.pkmprojects.mongodbserver.dto;

import java.time.Instant;

public record MysqlMonitorSnapshot(
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
