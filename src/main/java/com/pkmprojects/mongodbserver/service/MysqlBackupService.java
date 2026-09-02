package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.store.AuditStore;
import com.pkmprojects.mongodbserver.util.BackupLimits;
import com.pkmprojects.mongodbserver.util.Json;
import org.bson.Document;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

@Service
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlBackupService {

    private static final Logger log = LoggerFactory.getLogger(MysqlBackupService.class);
    static final int FORMAT_VERSION = 1;
    static final int INSERT_BATCH_SIZE = 1000;

    private final MysqlDatabaseRepository mysqlRepository;
    private final DatabaseNameValidator nameValidator;
    private final AuditStore auditStore;
    private final ApplicationEventPublisher publisher;
    private final DatabaseLockRegistry locks;
    private final Clock clock;

    public MysqlBackupService(@Autowired(required = false) MysqlDatabaseRepository mysqlRepository,
                              DatabaseNameValidator nameValidator,
                              AuditStore auditStore,
                              ApplicationEventPublisher publisher,
                              DatabaseLockRegistry locks,
                              Clock clock) {
        this.mysqlRepository = mysqlRepository;
        this.nameValidator = nameValidator;
        this.auditStore = auditStore;
        this.publisher = publisher;
        this.locks = locks;
        this.clock = clock;
    }

    public void requireDatabaseExists(String dbName) {
        nameValidator.validateMysqlDatabaseName(dbName);
        if (!mysqlRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
    }

    public BackupService.DatabaseBackupInfo describeDatabase(String dbName) {
        nameValidator.validateMysqlDatabaseName(dbName);
        boolean exists = mysqlRepository.databaseExists(dbName);
        int tableCount = 0;
        if (exists) {
            try { tableCount = mysqlRepository.listTables(dbName).size(); } catch (Exception ignored) {}
        }
        return new BackupService.DatabaseBackupInfo(dbName, exists, tableCount);
    }

    public BackupService.BackupResult writeBackup(String dbName, OutputStream out) {
        nameValidator.validateMysqlDatabaseName(dbName);
        requireDatabaseExists(dbName);
        List<String> tables;
        try {
            tables = mysqlRepository.listTables(dbName);
        } catch (Exception e) {
            throw new ProvisioningException("Could not back up database '" + dbName + "'", e);
        }
        long totalRows = 0;
        try (OutputStream gzip = new GZIPOutputStream(out)) {
            String header = "{\"formatVersion\":" + FORMAT_VERSION + ",\"engine\":\"MYSQL\",\"database\":"
                    + Json.jsonString(dbName) + ",\"backedUpAt\":" + Json.jsonString(clock.instant().toString()) + ",\"tables\":[";
            gzip.write(header.getBytes(StandardCharsets.UTF_8));
            boolean firstTable = true;
            for (String table : tables) {
                if (!firstTable) gzip.write(',');
                firstTable = false;
                List<String> columns;
                try { columns = mysqlRepository.getTableColumns(dbName, table); } catch (Exception e) {
                    throw new ProvisioningException("Could not read columns for table '" + table + "'", e);
                }
                String colJson = columns.stream().map(Json::jsonString).collect(java.util.stream.Collectors.joining(",", "[", "]"));
                String prefix = "{\"name\":" + Json.jsonString(table) + ",\"columns\":" + colJson + ",\"rows\":[";
                gzip.write(prefix.getBytes(StandardCharsets.UTF_8));
                boolean firstRow = true;
                int offset = 0;
                int batch = 1000;
                while (true) {
                    List<Map<String, Object>> rows;
                    try { rows = mysqlRepository.listRows(dbName, table, batch, offset); } catch (Exception e) {
                        throw new ProvisioningException("Could not read rows for table '" + table + "'", e);
                    }
                    if (rows.isEmpty()) break;
                    for (Map<String, Object> row : rows) {
                        if (!firstRow) gzip.write(',');
                        firstRow = false;
                        gzip.write(toJsonRow(row, columns).getBytes(StandardCharsets.UTF_8));
                        totalRows++;
                    }
                    offset += rows.size();
                    if (rows.size() < batch) break;
                }
                gzip.write("]}".getBytes(StandardCharsets.UTF_8));
            }
            gzip.write("]}".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProvisioningException("Could not back up database '" + dbName + "'", e);
        }
        audit(AuditEvent.BACKUP_CREATED, dbName);
        log.info("Backed up MYSQL database '{}': {} tables, {} rows", dbName, tables.size(), totalRows);
        return new BackupService.BackupResult(dbName, tables.size(), totalRows);
    }

    public BackupService.RestoreResult restore(String dbName, byte[] content, boolean confirmed) {
        nameValidator.validateMysqlDatabaseName(dbName);
        if (!confirmed) throw new NameNotAllowedException("Restore requires confirmation that existing data may be replaced");
        List<ParsedTable> parsed = readBackup(content);
        return locks.withLock(DatabaseEngineType.MYSQL.name() + ":" + dbName, () -> {
            if (!mysqlRepository.databaseExists(dbName)) {
                throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist; provision it first");
            }
            long totalRows = 0;
            for (ParsedTable pt : parsed) {
                nameValidator.validateMysqlTableName(pt.name);
                List<String> cols = resolveColumns(pt);
                if (!mysqlRepository.tableExists(dbName, pt.name)) {
                    createTable(dbName, pt.name, cols);
                } else if (!cols.isEmpty()) {
                    try {
                        List<String> existing = mysqlRepository.getTableColumns(dbName, pt.name);
                        java.util.Set<String> existingSet = new java.util.HashSet<>(existing);
                        for (String col : cols) {
                            if (!existingSet.contains(col)) {
                                mysqlRepository.executeInDatabase(dbName,
                                        "ALTER TABLE " + MysqlDatabaseRepository.quoteIdentifier(dbName) + "." + MysqlDatabaseRepository.quoteIdentifier(pt.name)
                                                + " ADD COLUMN " + MysqlDatabaseRepository.quoteIdentifier(col) + " TEXT");
                            }
                        }
                    } catch (Exception e) {
                        throw new ProvisioningException("Could not reconcile schema for table '" + pt.name + "'", e);
                    }
                }
                try {
                    mysqlRepository.truncateTable(dbName, pt.name);
                } catch (Exception e) {
                    // TRUNCATE fails when FK parent is referenced; fall back to DELETE
                    try {
                        mysqlRepository.getJdbcTemplate().execute(
                                "DELETE FROM " + MysqlDatabaseRepository.quoteIdentifier(dbName) + "." + MysqlDatabaseRepository.quoteIdentifier(pt.name));
                    } catch (Exception e2) {
                        e.addSuppressed(e2);
                        throw new ProvisioningException("Could not truncate table '" + pt.name + "'", e);
                    }
                }
                if (cols.isEmpty() || pt.rows.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < pt.rows.size(); i += INSERT_BATCH_SIZE) {
                    List<Map<String, Object>> batch = pt.rows.subList(i, Math.min(i + INSERT_BATCH_SIZE, pt.rows.size()));
                    for (Map<String, Object> row : batch) {
                        try {
                            // Filter row to only cols to avoid extra keys
                            Map<String, Object> filtered = new java.util.LinkedHashMap<>();
                            for (String c : cols) filtered.put(c, row.get(c));
                            mysqlRepository.insertRow(dbName, pt.name, filtered);
                        } catch (Exception e) {
                            throw new ProvisioningException("Could not restore rows into '" + pt.name + "'", e);
                        }
                    }
                }
                totalRows += pt.rows.size();
            }
            audit(AuditEvent.BACKUP_RESTORED, dbName);
            log.info("Restored MYSQL database '{}': {} tables, {} rows", dbName, parsed.size(), totalRows);
            return new BackupService.RestoreResult(dbName, parsed.size(), totalRows);
        });
    }

    private void createTable(String dbName, String table, List<String> columns) {
        if (columns.isEmpty()) {
            mysqlRepository.executeInDatabase(dbName, "CREATE TABLE " + MysqlDatabaseRepository.quoteIdentifier(dbName) + "." + MysqlDatabaseRepository.quoteIdentifier(table) + " (id TEXT)");
            return;
        }
        String cols = columns.stream()
                .map(c -> MysqlDatabaseRepository.quoteIdentifier(c) + " TEXT")
                .collect(java.util.stream.Collectors.joining(", "));
        mysqlRepository.executeInDatabase(dbName, "CREATE TABLE " + MysqlDatabaseRepository.quoteIdentifier(dbName) + "." + MysqlDatabaseRepository.quoteIdentifier(table) + " (" + cols + ")");
    }

    private List<String> resolveColumns(ParsedTable pt) {
        if (pt.columns != null && !pt.columns.isEmpty()) return pt.columns;
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : pt.rows) {
            keys.addAll(row.keySet());
        }
        return new ArrayList<>(keys);
    }

    private String toJsonRow(Map<String, Object> row, List<String> columns) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        for (String col : columns) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.jsonString(col)).append(':');
            Object v = row.get(col);
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v.toString());
            else if (v instanceof java.sql.Timestamp ts) sb.append(Json.jsonString(ts.toInstant().toString()));
            else if (v instanceof java.sql.Date d) sb.append(Json.jsonString(d.toString()));
            else sb.append(Json.jsonString(v.toString()));
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (columns.contains(e.getKey())) continue;
            if (e.getKey().startsWith("__mysql_")) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.jsonString(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v.toString());
            else sb.append(Json.jsonString(v.toString()));
        }
        sb.append('}');
        return sb.toString();
    }

    private List<ParsedTable> readBackup(byte[] content) {
        Document doc;
        try {
            String json = BackupLimits.readBoundedGzip(content, BackupLimits.MAX_DECOMPRESSED_BYTES);
            doc = Document.parse(json);
        } catch (NameNotAllowedException e) {
            throw e;
        } catch (Exception e) {
            throw new NameNotAllowedException("Backup file could not be read or is not a valid backup");
        }
        String engine = doc.getString("engine");
        if (engine != null && !"MYSQL".equals(engine)) throw new NameNotAllowedException("Backup is for " + engine + ", not MySQL");
        Integer ver = doc.getInteger("formatVersion");
        if (ver == null || ver != FORMAT_VERSION) throw new NameNotAllowedException("Unsupported backup format version: " + ver);
        List<Document> tables = doc.getList("tables", Document.class);
        if (tables == null) throw new NameNotAllowedException("Backup file does not contain any tables");
        if (tables.isEmpty()) {
            List<Document> cols = doc.getList("collections", Document.class);
            if (cols != null && !cols.isEmpty()) throw new NameNotAllowedException("Backup is for MongoDB, not MySQL");
        }
        List<ParsedTable> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Document t : tables) {
            String name = t.getString("name");
            List<String> columns = t.getList("columns", String.class);
            List<Document> rows = t.getList("rows", Document.class);
            if (name == null || name.isBlank()) throw new NameNotAllowedException("Backup contains a table without a name");
            if (!seen.add(name)) throw new NameNotAllowedException("Backup contains table '" + name + "' more than once");
            nameValidator.validateMysqlTableName(name);
            List<Map<String, Object>> rowMaps = new ArrayList<>();
            if (rows != null) {
                for (Document r : rows) {
                    rowMaps.add(new java.util.LinkedHashMap<>(r));
                }
            }
            out.add(new ParsedTable(name, columns == null ? List.of() : columns, rowMaps));
        }
        return out;
    }

    private void audit(String type, String dbName) {
        AuditEvent e = new AuditEvent(type, dbName, DatabaseEngineType.MYSQL, null, currentUser(), clock.instant());
        auditStore.save(e);
        publisher.publishEvent(new AuditEventRecorded(e));
    }

    private String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getName() != null ? a.getName() : "unknown";
    }

    private record ParsedTable(String name, List<String> columns, List<Map<String, Object>> rows) {}
}
