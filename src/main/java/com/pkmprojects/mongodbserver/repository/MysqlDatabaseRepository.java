package com.pkmprojects.mongodbserver.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-access gateway for MySQL server administration via JDBC.
 * Uses {@link JdbcTemplate} for cluster-wide operations.
 *
 * <p><strong>No {@code @Transactional}</strong> — DDL auto-commits in MySQL.</p>
 */
@Repository
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlDatabaseRepository {

    private final JdbcTemplate jdbcTemplate;
    private final String mysqlUri;
    private final String username;
    private final String password;

    public MysqlDatabaseRepository(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                                   @Value("${app.mysql.uri:jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}") String mysqlUri,
                                   @Value("${MYSQL_ROOT_USER:root}") String username,
                                   @Value("${MYSQL_ROOT_PASSWORD:root}") String password) {
        this.jdbcTemplate = mysqlJdbcTemplate;
        this.mysqlUri = mysqlUri;
        this.username = username;
        this.password = password;
    }

    public static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String quoteUser(String userName) {
        return "'" + userName.replace("'", "''") + "'@'%'";
    }

    public List<String> listDatabaseNames() {
        return jdbcTemplate.queryForList(
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('information_schema','mysql','performance_schema','sys') ORDER BY SCHEMA_NAME",
                String.class);
    }

    public boolean databaseExists(String dbName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?", Integer.class, dbName);
        return count != null && count > 0;
    }

    public long getDatabaseSize(String dbName) {
        Long size = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(data_length + index_length),0) FROM information_schema.TABLES WHERE table_schema = ?", Long.class, dbName);
        return size != null ? size : 0L;
    }

    public Map<String, Long> getDatabaseSizes() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT table_schema, COALESCE(SUM(data_length + index_length),0) AS sz FROM information_schema.TABLES WHERE table_schema NOT IN ('information_schema','mysql','performance_schema','sys') GROUP BY table_schema",
                rs -> {
                    String name = rs.getString("table_schema");
                    long sz = rs.getLong("sz");
                    sizes.put(name, sz);
                });
        // Ensure databases with no tables appear with 0
        for (String db : listDatabaseNames()) {
            sizes.putIfAbsent(db, 0L);
        }
        return sizes;
    }

    public void ping() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }

    public String getVersion() {
        return jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
    }

    public void createDatabase(String dbName) {
        String sql = "CREATE DATABASE " + quoteIdentifier(dbName) + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci";
        jdbcTemplate.execute(sql);
    }

    public void dropDatabase(String dbName) {
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + quoteIdentifier(dbName));
    }

    public void createUser(String dbName, String userName, String password) {
        String escaped = password.replace("'", "''");
        jdbcTemplate.execute("CREATE USER " + quoteUser(userName) + " IDENTIFIED BY '" + escaped + "'");
    }

    public void grantPrivileges(String dbName, String userName) {
        String grants = "SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER, CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE";
        jdbcTemplate.execute("GRANT " + grants + " ON " + quoteIdentifier(dbName) + ".* TO " + quoteUser(userName));
    }

    public void updateUserPassword(String dbName, String userName, String newPassword) {
        String escaped = newPassword.replace("'", "''");
        jdbcTemplate.execute("ALTER USER " + quoteUser(userName) + " IDENTIFIED BY '" + escaped + "'");
    }

    public void dropUser(String dbName, String userName) {
        try {
            jdbcTemplate.execute("REVOKE ALL PRIVILEGES ON " + quoteIdentifier(dbName) + ".* FROM " + quoteUser(userName));
        } catch (Exception ignored) {
        }
        jdbcTemplate.execute("DROP USER IF EXISTS " + quoteUser(userName));
    }

    public List<String> getUsers(String dbName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT user FROM mysql.db WHERE db = ? ORDER BY user",
                String.class, dbName);
    }

    // ── tables / rows ───────────────────────────────────────────────

    public List<String> listTables(String dbName) {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.TABLES WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name",
                String.class, dbName);
    }

    public boolean tableExists(String dbName, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE table_schema = ? AND table_name = ? AND table_type = 'BASE TABLE'",
                Integer.class, dbName, tableName);
        return count != null && count > 0;
    }

    public long countRows(String dbName, String tableName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName), Long.class);
        return count != null ? count : 0L;
    }

    public long getTableSize(String dbName, String tableName) {
        Long size = jdbcTemplate.queryForObject(
                "SELECT COALESCE(data_length + index_length,0) FROM information_schema.TABLES WHERE table_schema = ? AND table_name = ?",
                Long.class, dbName, tableName);
        return size != null ? size : 0L;
    }

    public List<String> getTableColumns(String dbName, String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.COLUMNS WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                String.class, dbName, tableName);
    }

    public String getPrimaryKeyColumn(String dbName, String tableName) {
        List<String> cols = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.KEY_COLUMN_USAGE WHERE table_schema = ? AND table_name = ? AND constraint_name = 'PRIMARY' ORDER BY ordinal_position",
                String.class, dbName, tableName);
        return cols.isEmpty() ? null : cols.get(0);
    }

    public List<Map<String, Object>> listRows(String dbName, String tableName, int limit, int offset) {
        String sql = "SELECT * FROM " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName) + " LIMIT ? OFFSET ?";
        return jdbcTemplate.queryForList(sql, limit, offset);
    }

    public void createTable(String dbName, String tableName, List<String> columns) {
        String cols = columns.stream()
                .map(c -> quoteIdentifier(c) + " TEXT")
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = cols.isEmpty()
                ? "CREATE TABLE " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName) + " (id TEXT)"
                : "CREATE TABLE " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName) + " (" + cols + ")";
        jdbcTemplate.execute(sql);
    }

    public void dropTable(String dbName, String tableName) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName));
    }

    public void truncateTable(String dbName, String tableName) {
        jdbcTemplate.execute("TRUNCATE TABLE " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName));
    }

    public void insertRow(String dbName, String tableName, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        List<String> cols = new java.util.ArrayList<>(values.keySet());
        String colList = cols.stream().map(MysqlDatabaseRepository::quoteIdentifier).collect(java.util.stream.Collectors.joining(", "));
        String placeholders = cols.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "INSERT INTO " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName) + " (" + colList + ") VALUES (" + placeholders + ")";
        Object[] args = cols.stream().map(values::get).toArray();
        jdbcTemplate.update(sql, args);
    }

    public void deleteRowByPk(String dbName, String tableName, String pkCol, Object pkVal) {
        String sql = "DELETE FROM " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName) + " WHERE " + quoteIdentifier(pkCol) + " = ?";
        jdbcTemplate.update(sql, pkVal);
    }

    public void deleteRowByAllColumns(String dbName, String tableName, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        List<String> cols = new java.util.ArrayList<>(values.keySet());
        String where = cols.stream().map(c -> quoteIdentifier(c) + " <=> ?").collect(java.util.stream.Collectors.joining(" AND "));
        String sql = "DELETE FROM " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName) + " WHERE " + where + " LIMIT 1";
        Object[] args = cols.stream().map(values::get).toArray();
        jdbcTemplate.update(sql, args);
    }

    public Map<String, Object> getTableStats(String dbName, String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT table_name, table_rows, data_length, index_length, auto_increment FROM information_schema.TABLES WHERE table_schema = ? AND table_name = ?",
                dbName, tableName);
        if (rows.isEmpty()) return Map.of("table_name", tableName, "table_rows", 0L, "data_length", 0L, "index_length", 0L);
        return rows.get(0);
    }

    public List<Map<String, Object>> getAllTableStats(String dbName) {
        return jdbcTemplate.queryForList(
                "SELECT table_name, table_rows, data_length, index_length, auto_increment FROM information_schema.TABLES WHERE table_schema = ? ORDER BY table_name",
                dbName);
    }

    public Map<String, Object> getMysqlMonitorData() {
        try {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
            result.put("version", version);
            Integer connCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.PROCESSLIST", Integer.class);
            result.put("connectionCount", connCount != null ? connCount : 0);
            Long uptime = jdbcTemplate.queryForObject("SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Uptime'", Long.class);
            if (uptime == null) {
                try {
                    String val = jdbcTemplate.queryForObject("SHOW GLOBAL STATUS LIKE 'Uptime'", (rs, rn) -> rs.getString(2));
                    if (val != null) uptime = Long.parseLong(val);
                } catch (Exception ignored) {}
            }
            if (uptime != null) result.put("uptimeSeconds", uptime);
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public void executeInDatabase(String dbName, String sql) {
        jdbcTemplate.execute(sql);
    }

    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}
