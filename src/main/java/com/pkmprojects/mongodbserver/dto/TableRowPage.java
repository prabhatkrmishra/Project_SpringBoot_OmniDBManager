package com.pkmprojects.mongodbserver.dto;

import java.util.List;
import java.util.Map;

/**
 * View model for one page of rows in a PostgreSQL table explorer.
 */
public record TableRowPage(
        String dbName,
        String tableName,
        int page,
        int pageSize,
        long totalCount,
        int totalPages,
        List<String> columns,
        List<Map<String, Object>> rows,
        boolean hasPrev,
        boolean hasNext) {

    public static TableRowPage empty(String dbName, String tableName, int page, int pageSize, List<String> columns) {
        return new TableRowPage(dbName, tableName, page, pageSize, 0, 0, columns, List.of(), false, false);
    }
}
