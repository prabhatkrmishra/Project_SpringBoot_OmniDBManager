package com.pkmprojects.mongodbserver.dto;

import java.util.List;

public record MysqlDatabaseStats(
        String dbName,
        int tableCount,
        long totalRows,
        long totalSizeBytes,
        List<TableStats> tables) {

    public String totalSizeLabel() {
        return ByteSize.format(totalSizeBytes);
    }
}
