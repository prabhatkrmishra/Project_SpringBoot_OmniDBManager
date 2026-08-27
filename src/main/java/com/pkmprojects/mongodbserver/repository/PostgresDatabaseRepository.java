package com.pkmprojects.mongodbserver.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-access gateway for PostgreSQL server administration via JDBC.
 * Uses {@link JdbcTemplate} for cluster-wide operations and per-database
 * {@link JdbcTemplate} instances for schema-scoped grants.
 *
 * <p><strong>No {@code @Transactional}</strong> — {@code CREATE/DROP DATABASE}
 * cannot run inside a transaction block (PG docs).</p>
 */
@Repository
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresDatabaseRepository {

    private final JdbcTemplate jdbcTemplate;
    private final String postgresUri;
    private final String username;
    private final String password;

    public PostgresDatabaseRepository(JdbcTemplate jdbcTemplate,
                                      @Value("${app.postgres.uri:jdbc:postgresql://127.0.0.1:9813/postgres}") String postgresUri,
                                      @Value("${POSTGRES_ROOT_USER:root}") String username,
                                      @Value("${POSTGRES_ROOT_PASSWORD:root}") String password) {
        this.jdbcTemplate = jdbcTemplate;
        this.postgresUri = postgresUri;
        this.username = username;
        this.password = password;
    }

    public static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public void executeInDatabase(String dbName, String sql) {
        jdbcFor(dbName).execute(sql);
    }

    public void createTable(String dbName, String tableName, List<String> columns) {
        String cols = columns.stream()
                .map(c -> quoteIdentifier(c) + " TEXT")
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = cols.isEmpty()
                ? "CREATE TABLE public." + quoteIdentifier(tableName) + " (id TEXT)"
                : "CREATE TABLE public." + quoteIdentifier(tableName) + " (" + cols + ")";
        jdbcFor(dbName).execute(sql);
    }

    public void dropTable(String dbName, String tableName) {
        jdbcFor(dbName).execute("DROP TABLE IF EXISTS public." + quoteIdentifier(tableName) + " CASCADE");
    }

    public void truncateTable(String dbName, String tableName) {
        jdbcFor(dbName).execute("TRUNCATE TABLE public." + quoteIdentifier(tableName) + " CASCADE");
    }

    public void deleteRowByCtid(String dbName, String tableName, String ctid) {
        jdbcFor(dbName).update("DELETE FROM public." + quoteIdentifier(tableName) + " WHERE ctid = ?::tid", ctid);
    }

    public void insertRow(String dbName, String tableName, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        List<String> cols = new java.util.ArrayList<>(values.keySet());
        String colList = cols.stream().map(PostgresDatabaseRepository::quoteIdentifier).collect(java.util.stream.Collectors.joining(", "));
        String placeholders = cols.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "INSERT INTO public." + quoteIdentifier(tableName) + " (" + colList + ") VALUES (" + placeholders + ")";
        Object[] args = cols.stream().map(values::get).toArray();
        jdbcFor(dbName).update(sql, args);
    }

    public void insertRows(String dbName, String tableName, List<String> columns, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty() || columns == null || columns.isEmpty()) return;
        JdbcTemplate target = jdbcFor(dbName);
        String colList = columns.stream().map(PostgresDatabaseRepository::quoteIdentifier).collect(java.util.stream.Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "INSERT INTO public." + quoteIdentifier(tableName) + " (" + colList + ") VALUES (" + placeholders + ")";
        List<Object[]> batch = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object[] args = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                Object v = row.get(columns.get(i));
                // Preserve native types so PG can cast correctly; only stringify unknown types
                if (v == null) {
                    args[i] = null;
                } else if (v instanceof Number || v instanceof Boolean || v instanceof java.sql.Timestamp
                        || v instanceof java.sql.Date || v instanceof java.util.Date) {
                    args[i] = v;
                } else {
                    args[i] = v.toString();
                }
            }
            batch.add(args);
        }
        target.batchUpdate(sql, batch);
    }

    private JdbcTemplate jdbcFor(String dbName) {
        String url = urlFor(dbName);
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return new JdbcTemplate(ds);
    }

    String urlFor(String dbName) {
        // jdbc:postgresql://host:port/db?params -> replace db part
        String uri = postgresUri;
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            return uri;
        }
        int slash = uri.indexOf('/', schemeEnd + 3);
        int q = uri.indexOf('?', schemeEnd + 3);
        if (slash < 0) {
            // No slash: host[:port][?query] -> insert /dbName before query
            if (q >= 0) {
                return uri.substring(0, q) + "/" + dbName + uri.substring(q);
            }
            return uri + "/" + dbName;
        }
        String prefix = uri.substring(0, slash + 1);
        String suffix = q >= 0 ? uri.substring(q) : "";
        return prefix + dbName + suffix;
    }

    public List<String> listDatabaseNames() {
        return jdbcTemplate.queryForList(
                "SELECT datname FROM pg_database WHERE datistemplate = false AND datname NOT IN ('postgres','template0','template1') ORDER BY datname",
                String.class);
    }

    public boolean databaseExists(String dbName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_database WHERE datname = ?", Integer.class, dbName);
        return count != null && count > 0;
    }

    public long getDatabaseSize(String dbName) {
        Long size = jdbcTemplate.queryForObject("SELECT pg_database_size(?)", Long.class, dbName);
        return size != null ? size : 0L;
    }

    public Map<String, Long> getDatabaseSizes() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT datname, pg_database_size(datname) AS sz FROM pg_database WHERE datistemplate = false",
                rs -> {
                    String name = rs.getString("datname");
                    long sz = rs.getLong("sz");
                    sizes.put(name, sz);
                });
        return sizes;
    }

    public void ping() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }

    public String getVersion() {
        return jdbcTemplate.queryForObject("SELECT current_setting('server_version')", String.class);
    }

    public void createDatabase(String dbName, String owner) {
        String sql = "CREATE DATABASE " + quoteIdentifier(dbName)
                + " OWNER " + quoteIdentifier(owner)
                + " TEMPLATE template0 ENCODING 'UTF8'";
        jdbcTemplate.execute(sql);
    }

    public void dropDatabase(String dbName) {
        // Terminate backends except our own
        jdbcTemplate.update("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()", dbName);
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + quoteIdentifier(dbName));
    }

    public void createUser(String dbName, String userName, String password) {
        String escaped = password.replace("'", "''");
        jdbcTemplate.execute("CREATE ROLE " + quoteIdentifier(userName) + " WITH LOGIN PASSWORD '" + escaped + "'");
    }

    public void grantPrivileges(String dbName, String userName) {
        jdbcTemplate.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(dbName) + " TO " + quoteIdentifier(userName));
        JdbcTemplate target = jdbcFor(dbName);
        // Hardening: revoke public create on this DB's public schema (PG15+ already does, but explicit for older templates)
        try {
            target.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        } catch (Exception ignored) {
            // Idempotent — may already be revoked or insufficient privilege in some setups
        }
        target.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + quoteIdentifier(userName));
        target.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO " + quoteIdentifier(userName));
        target.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO " + quoteIdentifier(userName));
    }

    public void updateUserPassword(String dbName, String userName, String newPassword) {
        String escaped = newPassword.replace("'", "''");
        jdbcTemplate.execute("ALTER ROLE " + quoteIdentifier(userName) + " WITH PASSWORD '" + escaped + "'");
    }

    public void dropUser(String dbName, String userName) {
        try {
            jdbcTemplate.execute("REVOKE ALL ON DATABASE " + quoteIdentifier(dbName) + " FROM " + quoteIdentifier(userName));
        } catch (Exception ignored) {
            // REVOKE may fail if DB already dropped
        }
        try {
            JdbcTemplate target = jdbcFor(dbName);
            target.execute("REVOKE ALL ON SCHEMA public FROM " + quoteIdentifier(userName));
        } catch (Exception ignored) {
            // target DB may already be gone
        }
        jdbcTemplate.execute("DROP ROLE IF EXISTS " + quoteIdentifier(userName));
    }

    public List<String> getUsers(String dbName) {
        return jdbcTemplate.queryForList(
                "SELECT usename FROM pg_user WHERE has_database_privilege(usename, ?, 'CONNECT') AND usename NOT LIKE 'pg_%' ORDER BY usename",
                String.class, dbName);
    }

    public List<String> listTables(String dbName) {
        JdbcTemplate target = jdbcFor(dbName);
        return target.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name",
                String.class);
    }

    public boolean tableExists(String dbName, String tableName) {
        JdbcTemplate target = jdbcFor(dbName);
        Integer count = target.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    public long countRows(String dbName, String tableName) {
        JdbcTemplate target = jdbcFor(dbName);
        Long count = target.queryForObject("SELECT COUNT(*) FROM public." + quoteIdentifier(tableName), Long.class);
        return count != null ? count : 0L;
    }

    public long getTableSizeQualified(String dbName, String tableName) {
        JdbcTemplate target = jdbcFor(dbName);
        Long size = target.queryForObject("SELECT pg_total_relation_size(?)", Long.class, "public." + quoteIdentifier(tableName));
        return size != null ? size : 0L;
    }

    public List<Map<String, Object>> listRows(String dbName, String tableName, int limit, int offset) {
        JdbcTemplate target = jdbcFor(dbName);
        String sql = "SELECT * FROM public." + quoteIdentifier(tableName) + " LIMIT ? OFFSET ?";
        return target.queryForList(sql, limit, offset);
    }

    public List<Map<String, Object>> listRowsWithCtid(String dbName, String tableName, int limit, int offset) {
        JdbcTemplate target = jdbcFor(dbName);
        String sql = "SELECT *, ctid::text AS __pg_ctid FROM public." + quoteIdentifier(tableName) + " LIMIT ? OFFSET ?";
        return target.queryForList(sql, limit, offset);
    }

    public List<String> getTableColumns(String dbName, String tableName) {
        JdbcTemplate target = jdbcFor(dbName);
        return target.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position",
                String.class, tableName);
    }

    public Map<String, Object> getTableStats(String dbName, String tableName) {
        JdbcTemplate target = jdbcFor(dbName);
        List<Map<String, Object>> rows = target.queryForList(
                "SELECT relname, n_live_tup, n_dead_tup, last_vacuum, last_autovacuum, last_analyze, last_autoanalyze FROM pg_stat_user_tables WHERE schemaname = 'public' AND relname = ?",
                tableName);
        if (rows.isEmpty()) {
            return Map.of("relname", tableName, "n_live_tup", 0L, "n_dead_tup", 0L);
        }
        return rows.get(0);
    }

    public List<Map<String, Object>> getAllTableStats(String dbName) {
        JdbcTemplate target = jdbcFor(dbName);
        return target.queryForList(
                "SELECT relname, n_live_tup, n_dead_tup, last_vacuum, last_autovacuum, last_analyze, last_autoanalyze FROM pg_stat_user_tables WHERE schemaname = 'public' ORDER BY relname");
    }

    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    public Map<String, Object> getPostgresMonitorData() {
        try {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            Integer connCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_stat_activity", Integer.class);
            result.put("connectionCount", connCount != null ? connCount : 0);
            String version = jdbcTemplate.queryForObject("SELECT current_setting('server_version')", String.class);
            result.put("version", version);
            java.sql.Timestamp start = jdbcTemplate.queryForObject("SELECT pg_postmaster_start_time()", java.sql.Timestamp.class);
            if (start != null) {
                long uptime = (System.currentTimeMillis() - start.getTime()) / 1000;
                result.put("uptimeSeconds", uptime);
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
