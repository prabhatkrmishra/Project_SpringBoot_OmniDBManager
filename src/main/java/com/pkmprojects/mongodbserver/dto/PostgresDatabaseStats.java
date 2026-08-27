package com.pkmprojects.mongodbserver.dto;

import java.util.List;

/**
 * View model for PostgreSQL database statistics from pg_stat_user_tables + pg_database_size.
 */
public record PostgresDatabaseStats(
        String dbName,
        int tableCount,
        long totalRows,
        long totalSizeBytes,
        List<TableStats> tables) {

    public String totalSizeLabel() {
        return ByteSize.format(totalSizeBytes);
    }
}
