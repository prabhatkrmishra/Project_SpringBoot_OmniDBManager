package com.pkmprojects.mongodbserver.dto;

/**
 * View model for the health dashboard. Metrics that require elevated MongoDB
 * privileges (version, uptime, connections) are {@code null} when unavailable
 * rather than failing the page.
 *
 * <p>Phase 2 adds per-engine reachability: {@code mongoReachable} and
 * {@code postgresReachable}. {@code reachable} is kept as an alias for
 * {@code mongoReachable} for backward compat.</p>
 */
public record ServerHealth(
        boolean reachable,
        String version,
        Long uptimeSeconds,
        int databaseCount,
        Long totalStorageBytes,
        Integer connectionCount,
        boolean mongoReachable,
        boolean postgresReachable,
        String postgresVersion,
        boolean postgresEnabled,
        boolean mysqlReachable,
        String mysqlVersion,
        boolean mysqlEnabled,
        boolean mongoEnabled) {

    /**
     * Legacy constructor — defaults postgres/mysql/mongo fields to disabled.
     */
    public ServerHealth(boolean reachable, String version, Long uptimeSeconds,
                        int databaseCount, Long totalStorageBytes, Integer connectionCount) {
        this(reachable, version, uptimeSeconds, databaseCount, totalStorageBytes, connectionCount,
                reachable, false, null, false, false, null, false, true);
    }

    /**
     * Postgres-era constructor — defaults mysql/mongo fields to disabled.
     */
    public ServerHealth(boolean reachable, String version, Long uptimeSeconds,
                        int databaseCount, Long totalStorageBytes, Integer connectionCount,
                        boolean mongoReachable, boolean postgresReachable, String postgresVersion, boolean postgresEnabled) {
        this(reachable, version, uptimeSeconds, databaseCount, totalStorageBytes, connectionCount,
                mongoReachable, postgresReachable, postgresVersion, postgresEnabled, false, null, false, true);
    }

    /**
     * Human-readable uptime (e.g. {@code 3d 4h 12m}), or {@code null} when unknown.
     */
    public String uptime() {
        if (uptimeSeconds == null) {
            return null;
        }
        long total = uptimeSeconds;
        long days = total / 86400;
        total %= 86400;
        long hours = total / 3600;
        total %= 3600;
        long minutes = total / 60;
        total %= 60;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + total + "s";
        }
        return total + "s";
    }

    /**
     * Human-readable total storage (e.g. {@code 12.5 MB}), or {@code null} when unknown.
     */
    public String storageLabel() {
        if (totalStorageBytes == null) {
            return null;
        }
        double bytes = totalStorageBytes;
        if (bytes < 1024) {
            return String.format("%.0f B", bytes);
        }
        if (bytes < 1048576) {
            return String.format("%.1f KB", bytes / 1024);
        }
        if (bytes < 1073741824) {
            return String.format("%.1f MB", bytes / 1048576);
        }
        return String.format("%.2f GB", bytes / 1073741824);
    }
}
