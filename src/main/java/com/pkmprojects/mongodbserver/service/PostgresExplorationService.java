package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.TableInfo;
import com.pkmprojects.mongodbserver.dto.TableRowPage;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import com.pkmprojects.mongodbserver.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher publisher;
    private final DatabaseLockRegistry locks;
    private final Clock clock;

    public PostgresExplorationService(@Autowired(required = false) PostgresDatabaseRepository postgresRepository,
                                      DatabaseNameValidator nameValidator,
                                      @Autowired(required = false) AuditLogRepository auditLogRepository,
                                      @Autowired(required = false) ApplicationEventPublisher publisher,
                                      DatabaseLockRegistry locks,
                                      Clock clock) {
        this.postgresRepository = postgresRepository;
        this.nameValidator = nameValidator;
        this.auditLogRepository = auditLogRepository;
        this.publisher = publisher;
        this.locks = locks;
        this.clock = clock;
    }

    public PostgresExplorationService(PostgresDatabaseRepository postgresRepository,
                                      DatabaseNameValidator nameValidator) {
        this(postgresRepository, nameValidator, null, null, new DatabaseLockRegistry(), Clock.systemUTC());
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
            rows = postgresRepository.listRowsWithCtid(dbName, tableName, DEFAULT_PAGE_SIZE, offset);
        } catch (Exception e) {
            log.warn("Could not read rows for {}.{}", dbName, tableName, e);
            throw new ProvisioningException("Could not read rows for table '" + tableName + "'", e);
        }

        return new TableRowPage(dbName, tableName, safePage, DEFAULT_PAGE_SIZE, totalCount, totalPages,
                columns, rows, safePage > 1, safePage < totalPages);
    }

    public void createTable(String dbName, String tableName, List<String> columns) {
        nameValidator.validatePostgresDatabaseName(dbName);
        validateTableName(tableName);
        List<String> effective = columns == null ? List.of()
                : columns.stream().map(String::trim).filter(s -> !s.isBlank()).map(s -> s.toLowerCase(java.util.Locale.ROOT)).distinct().toList();
        for (String col : effective) {
            validateTableName(col);
            if ("__pg_ctid".equals(col) || "__ctid".equals(col) || "ctid".equals(col)
                    || "__new_col".equals(col) || "__new_val".equals(col) || "_csrf".equals(col)) {
                throw new NameNotAllowedException("Column name '" + col + "' is reserved");
            }
        }
        locks.withLock(DatabaseEngineType.POSTGRES.name() + ":" + dbName, () -> {
            requireDatabase(dbName);
            if (postgresRepository.tableExists(dbName, tableName)) {
                throw new DatabaseAlreadyExistsException("Table '" + tableName + "' already exists");
            }
            try {
                postgresRepository.createTable(dbName, tableName, effective);
            } catch (Exception e) {
                log.error("Failed to create table {}.{}", dbName, tableName, e);
                throw new ProvisioningException("Could not create table '" + tableName + "'", e);
            }
            audit(AuditEvent.TABLE_CREATED, dbName, tableName);
            log.info("Created table {}.{}", dbName, tableName);
        });
    }

    public void dropTable(String dbName, String tableName) {
        nameValidator.validatePostgresDatabaseName(dbName);
        validateTableName(tableName);
        locks.withLock(DatabaseEngineType.POSTGRES.name() + ":" + dbName, () -> {
            requireDatabase(dbName);
            requireTable(dbName, tableName);
            try {
                postgresRepository.dropTable(dbName, tableName);
            } catch (Exception e) {
                log.error("Failed to drop table {}.{}", dbName, tableName, e);
                throw new ProvisioningException("Could not drop table '" + tableName + "'", e);
            }
            audit(AuditEvent.TABLE_DROPPED, dbName, tableName);
            log.info("Dropped table {}.{}", dbName, tableName);
        });
    }

    public void truncateTable(String dbName, String tableName) {
        nameValidator.validatePostgresDatabaseName(dbName);
        validateTableName(tableName);
        locks.withLock(DatabaseEngineType.POSTGRES.name() + ":" + dbName, () -> {
            requireDatabase(dbName);
            requireTable(dbName, tableName);
            try {
                postgresRepository.truncateTable(dbName, tableName);
            } catch (Exception e) {
                log.error("Failed to truncate table {}.{}", dbName, tableName, e);
                throw new ProvisioningException("Could not truncate table '" + tableName + "'", e);
            }
            audit(AuditEvent.TABLE_TRUNCATED, dbName, tableName);
            log.info("Truncated table {}.{}", dbName, tableName);
        });
    }

    public void insertRow(String dbName, String tableName, Map<String, Object> values) {
        nameValidator.validatePostgresDatabaseName(dbName);
        validateTableName(tableName);
        if (values == null || values.isEmpty()) throw new NameNotAllowedException("Row must have at least one column");
        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String col = e.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            if ("__pg_ctid".equals(col) || "__ctid".equals(col) || "ctid".equals(col)
                    || "__new_col".equals(col) || "__new_val".equals(col) || "_csrf".equals(col)) {
                throw new NameNotAllowedException("Column name '" + col + "' is reserved");
            }
            validateTableName(col);
            Object v = e.getValue();
            if (v instanceof String s && s.isBlank()) v = null;
            normalized.put(col, v);
        }
        locks.withLock(DatabaseEngineType.POSTGRES.name() + ":" + dbName, () -> {
            requireDatabase(dbName);
            requireTable(dbName, tableName);
            try {
                List<String> existing = postgresRepository.getTableColumns(dbName, tableName);
                java.util.Set<String> existingSet = new java.util.HashSet<>(existing);
                for (String col : normalized.keySet()) {
                    if (!existingSet.contains(col)) {
                        postgresRepository.executeInDatabase(dbName,
                                "ALTER TABLE public." + PostgresDatabaseRepository.quoteIdentifier(tableName)
                                        + " ADD COLUMN " + PostgresDatabaseRepository.quoteIdentifier(col) + " TEXT");
                    }
                }
                postgresRepository.insertRow(dbName, tableName, normalized);
            } catch (Exception e) {
                if (e instanceof ProvisioningException) throw (ProvisioningException) e;
                log.error("Failed to insert row into {}.{}", dbName, tableName, e);
                throw new ProvisioningException("Could not insert row into '" + tableName + "'", e);
            }
            audit(AuditEvent.ROW_INSERTED, dbName, tableName);
        });
    }

    public void deleteRow(String dbName, String tableName, String ctid) {
        nameValidator.validatePostgresDatabaseName(dbName);
        validateTableName(tableName);
        if (ctid == null || ctid.isBlank()) throw new NameNotAllowedException("Row identifier is required");
        locks.withLock(DatabaseEngineType.POSTGRES.name() + ":" + dbName, () -> {
            requireDatabase(dbName);
            requireTable(dbName, tableName);
            try {
                postgresRepository.deleteRowByCtid(dbName, tableName, ctid);
            } catch (Exception e) {
                log.error("Failed to delete row from {}.{}", dbName, tableName, e);
                throw new ProvisioningException("Could not delete row from '" + tableName + "'", e);
            }
            audit(AuditEvent.ROW_DELETED, dbName, tableName);
        });
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

    private void audit(String eventType, String dbName, String tableName) {
        if (auditLogRepository == null || publisher == null || clock == null) return;
        String user = currentUser();
        AuditEvent e = new AuditEvent(eventType, dbName, DatabaseEngineType.POSTGRES, tableName, user, clock.instant());
        try { auditLogRepository.save(e); } catch (Exception ex) { log.warn("Could not save audit {}", eventType, ex); }
        try { publisher.publishEvent(new AuditEventRecorded(e)); } catch (Exception ex) { log.warn("Could not publish audit {}", eventType, ex); }
    }

    private String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getName() != null ? a.getName() : "unknown";
    }
}
