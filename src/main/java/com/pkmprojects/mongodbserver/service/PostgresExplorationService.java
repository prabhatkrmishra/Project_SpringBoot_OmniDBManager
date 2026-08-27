package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.TableInfo;
import com.pkmprojects.mongodbserver.dto.TableRowPage;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import com.pkmprojects.mongodbserver.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Read-only exploration for PostgreSQL: tables of a database and paginated rows.
 */
@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresExplorationService {

    static final int DEFAULT_PAGE_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(PostgresExplorationService.class);

    private final PostgresDatabaseRepository postgresRepository;
    private final DatabaseNameValidator nameValidator;

    public PostgresExplorationService(@Autowired(required = false) PostgresDatabaseRepository postgresRepository,
                                      DatabaseNameValidator nameValidator) {
        this.postgresRepository = postgresRepository;
        this.nameValidator = nameValidator;
    }

    public List<TableInfo> listTables(String dbName) {
        nameValidator.validatePostgresDatabaseName(dbName);
        requireDatabase(dbName);
        List<String> names;
        try {
            names = postgresRepository.listTables(dbName);
        } catch (Exception e) {
            log.warn("Could not list tables for {}", dbName, e);
            throw new ProvisioningException("Could not list tables for database '" + dbName + "'", e);
        }
        return names.stream()
                .map(name -> {
                    long count = 0;
                    try {
                        count = postgresRepository.countRows(dbName, name);
                    } catch (Exception e) {
                        log.warn("Could not count rows for {}.{}", dbName, name, e);
                    }
                    return new TableInfo(name, count);
                })
                .toList();
    }

    public TableRowPage getRows(String dbName, String tableName, int page) {
        nameValidator.validatePostgresDatabaseName(dbName);
        validateTableName(tableName);
        requireDatabase(dbName);
        requireTable(dbName, tableName);

        long totalCount;
        try {
            totalCount = postgresRepository.countRows(dbName, tableName);
        } catch (Exception e) {
            log.warn("Could not count rows for {}.{}", dbName, tableName, e);
            throw new ProvisioningException("Could not count rows for table '" + tableName + "'", e);
        }

        int totalPages = (int) Math.ceil((double) totalCount / DEFAULT_PAGE_SIZE);
        int safePage = Math.max(1, Math.min(page, Math.max(totalPages, 1)));
        int offset = (safePage - 1) * DEFAULT_PAGE_SIZE;

        List<String> columns;
        try {
            columns = postgresRepository.getTableColumns(dbName, tableName);
        } catch (Exception e) {
            log.warn("Could not read columns for {}.{}", dbName, tableName, e);
            throw new ProvisioningException("Could not read columns for table '" + tableName + "'", e);
        }

        List<Map<String, Object>> rows;
        try {
            rows = postgresRepository.listRows(dbName, tableName, DEFAULT_PAGE_SIZE, offset);
        } catch (Exception e) {
            log.warn("Could not read rows for {}.{}", dbName, tableName, e);
            throw new ProvisioningException("Could not read rows for table '" + tableName + "'", e);
        }

        return new TableRowPage(dbName, tableName, safePage, DEFAULT_PAGE_SIZE, totalCount, totalPages,
                columns, rows, safePage > 1, safePage < totalPages);
    }

    public void ensureTableExists(String dbName, String tableName) {
        nameValidator.validatePostgresDatabaseName(dbName);
        validateTableName(tableName);
        requireDatabase(dbName);
        requireTable(dbName, tableName);
    }

    public void writeAllRowsAsJson(String dbName, String tableName, OutputStream out) {
        nameValidator.validatePostgresDatabaseName(dbName);
        validateTableName(tableName);
        requireDatabase(dbName);
        requireTable(dbName, tableName);
        try {
            List<String> columns = postgresRepository.getTableColumns(dbName, tableName);
            java.util.Set<String> columnSet = new java.util.HashSet<>(columns);
            out.write('[');
            boolean first = true;
            int offset = 0;
            int batch = 1000;
            while (true) {
                List<Map<String, Object>> rows = postgresRepository.listRows(dbName, tableName, batch, offset);
                if (rows.isEmpty()) break;
                for (Map<String, Object> row : rows) {
                    if (!first) out.write(',');
                    first = false;
                    out.write(toJson(row, columns, columnSet).getBytes(StandardCharsets.UTF_8));
                }
                offset += rows.size();
                if (rows.size() < batch) break;
            }
            out.write(']');
        } catch (IOException e) {
            throw new ProvisioningException("Could not export table '" + tableName + "'", e);
        } catch (Exception e) {
            if (e instanceof ProvisioningException) throw (ProvisioningException) e;
            throw new ProvisioningException("Could not export table '" + tableName + "'", e);
        }
    }

    private String toJson(Map<String, Object> row, List<String> columns, java.util.Set<String> columnSet) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        for (String col : columns) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.jsonString(col)).append(':');
            Object val = row.get(col);
            sb.append(jsonValue(val));
        }
        // Include any extra keys not in columns (should not happen, but be safe)
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (columnSet.contains(e.getKey())) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.jsonString(e.getKey())).append(':').append(jsonValue(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    private String jsonValue(Object val) {
        if (val == null) return "null";
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof java.sql.Timestamp ts) return Json.jsonString(ts.toInstant().toString());
        if (val instanceof java.sql.Date d) return Json.jsonString(d.toString());
        if (val instanceof java.util.Date d) return Json.jsonString(d.toInstant().toString());
        return Json.jsonString(val.toString());
    }

    private void requireDatabase(String dbName) {
        try {
            if (!postgresRepository.databaseExists(dbName)) {
                throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
            }
        } catch (DatabaseNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not check existence of database {}", dbName, e);
            throw new ProvisioningException("Could not check database '" + dbName + "'", e);
        }
    }

    private void requireTable(String dbName, String tableName) {
        try {
            if (!postgresRepository.tableExists(dbName, tableName)) {
                throw new DatabaseNotFoundException("Table '" + tableName + "' does not exist");
            }
        } catch (DatabaseNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not check existence of table {}.{}", dbName, tableName, e);
            throw new ProvisioningException("Could not check table '" + tableName + "'", e);
        }
    }

    private void validateTableName(String tableName) {
        nameValidator.validateTableName(tableName);
    }
}
