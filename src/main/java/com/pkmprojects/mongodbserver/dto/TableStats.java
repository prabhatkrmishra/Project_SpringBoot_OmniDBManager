package com.pkmprojects.mongodbserver.dto;

/**
 * View model for one PostgreSQL table's statistics from pg_stat_user_tables + pg_total_relation_size.
 */
public record TableStats(
        String name,
        long rowCount,
        long sizeBytes,
        long liveTuples,
        long deadTuples,
        String lastVacuum,
        String lastAnalyze) {

    public String sizeLabel() {
        return ByteSize.format(sizeBytes);
    }
}
