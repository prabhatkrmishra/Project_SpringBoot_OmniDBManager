package com.pkmprojects.mongodbserver.dto;

/**
 * View model for one table's statistics (PostgreSQL: pg_stat_user_tables + pg_total_relation_size;
 * MySQL: information_schema.TABLES + COUNT(*)).
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
