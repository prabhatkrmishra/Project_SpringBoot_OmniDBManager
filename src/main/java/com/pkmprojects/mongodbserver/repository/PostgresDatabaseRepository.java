package com.pkmprojects.mongodbserver.repository;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Logger log = LoggerFactory.getLogger(PostgresDatabaseRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final String postgresUri;
    private final String username;
    private final String password;
    private final ConcurrentHashMap<String, HikariDataSource> perDbDataSources = new ConcurrentHashMap<>();
    /** Databases currently being dropped — jdbcFor refuses to create a pool for these (P1 race). */
    private final java.util.Set<String> deletingDatabases = ConcurrentHashMap.newKeySet();

    public PostgresDatabaseRepository(@org.springframework.beans.factory.annotation.Qualifier("postgresJdbcTemplate") JdbcTemplate jdbcTemplate,
                                      @Value("${app.postgres.uri:jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10}") String postgresUri,
                                      @Value("${POSTGRES_ROOT_USER:root}") String username,
                                      @Value("${POSTGRES_ROOT_PASSWORD:root}") String password) {
        this.jdbcTemplate = jdbcTemplate;
        this.postgresUri = postgresUri;
        this.username = username;
        this.password = password;
    }

    @PreDestroy
    void closePerDbPools() {
        // Drain atomically: snapshot the pools, clear the map, then close. The
        // old values()-then-clear() could lose a pool added concurrently between
        // the two calls, leaking it without close.
        java.util.List<HikariDataSource> pools = new java.util.ArrayList<>(perDbDataSources.values());
        perDbDataSources.clear();
        for (HikariDataSource ds : pools) {
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    private void evictPool(String dbName) {
        HikariDataSource ds = perDbDataSources.remove(dbName);
        if (ds != null) {
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    public static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("Identifier must not be null or empty");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String escapePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password must not be null");
        }
        // Defense-in-depth: reject passwords containing SQL metacharacters that could
        // enable injection if the driver ever allows multi-statement execution.
        if (password.contains(";") || password.contains("--") || password.contains("/*") || password.contains("*/")) {
            throw new IllegalArgumentException("Password contains disallowed SQL metacharacters");
        }
        return password.replace("'", "''");
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
        if (dbName == null || dbName.isEmpty()) {
            throw new IllegalArgumentException("dbName must not be null or empty");
        }
        if (deletingDatabases.contains(dbName)) {
            throw new IllegalStateException("Database '" + dbName + "' is being deleted");
        }
        HikariDataSource ds = perDbDataSources.computeIfAbsent(dbName, key -> {
            HikariDataSource hds = new HikariDataSource();
            try {
                hds.setJdbcUrl(urlFor(key));
                hds.setUsername(username);
                hds.setPassword(password);
                hds.setDriverClassName("org.postgresql.Driver");
                hds.setMaximumPoolSize(2);
                hds.setMinimumIdle(0);
                hds.setConnectionTimeout(10000);
                hds.setValidationTimeout(2000);
                hds.setIdleTimeout(30000);
                hds.setMaxLifetime(120000);
                // Probe: fail fast if the database doesn't exist so we don't cache
                // a pool for a non-existent DB (P2). If this throws, computeIfAbsent
                // does not retain the mapping and the pool is closed below.
                try (java.sql.Connection c = hds.getConnection()) {
                    // connection acquired — DB reachable
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("Could not connect to database '" + key + "': " + e.getMessage(), e);
                }
                return hds;
            } catch (RuntimeException e) {
                try { hds.close(); } catch (Exception ignored) {}
                throw e;
            }
        });
        JdbcTemplate tpl = new JdbcTemplate(ds);
        tpl.setQueryTimeout(10);
        return tpl;
    }

    String urlFor(String dbName) {
        // jdbc:postgresql://host:port/db?params -> replace db part
        String uri = postgresUri;
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException("Invalid JDBC URI — no scheme (://): " + uri);
        }
        String encoded = encodePathSegment(dbName);
        int slash = uri.indexOf('/', schemeEnd + 3);
        int q = uri.indexOf('?', schemeEnd + 3);
        if (slash < 0) {
            // No slash: host[:port][?query] -> insert /dbName before query
            if (q >= 0) {
                return uri.substring(0, q) + "/" + encoded + uri.substring(q);
            }
            return uri + "/" + encoded;
        }
        String prefix = uri.substring(0, slash + 1);
        String suffix = q >= 0 ? uri.substring(q) : "";
        return prefix + encoded + suffix;
    }

    /** Percent-encode a value for use as a URL path segment (spaces as %20, not +). */
    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
        jdbcTemplate.query("SELECT datname, pg_database_size(datname) AS sz FROM pg_database WHERE datistemplate = false AND datname NOT IN ('postgres','template0','template1')",
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
        deletingDatabases.remove(dbName);
        String sql = "CREATE DATABASE " + quoteIdentifier(dbName)
                + " OWNER " + quoteIdentifier(owner)
                + " TEMPLATE template0 ENCODING 'UTF8'";
        jdbcTemplate.execute(sql);
    }

    public void dropDatabase(String dbName) {
        // Mark as deleting first so a concurrent jdbcFor(dbName) cannot recreate
        // a pool between our evict and the DROP (P1 race). Cleared in finally so
        // a failed DROP doesn't leave the DB permanently unqueryable.
        deletingDatabases.add(dbName);
        try {
            try {
                jdbcTemplate.query("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()", (rs, rowNum) -> null, dbName);
            } catch (Exception ignored) {
            }
            evictPool(dbName);
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + quoteIdentifier(dbName));
        } finally {
            deletingDatabases.remove(dbName);
        }
    }

    public void createUser(String dbName, String userName, String password) {
        String escaped = escapePassword(password);
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
        String escaped = escapePassword(newPassword);
        jdbcTemplate.execute("ALTER ROLE " + quoteIdentifier(userName) + " WITH PASSWORD '" + escaped + "'");
    }

    public void dropUser(String dbName, String userName) {
        try {
            jdbcTemplate.execute("REVOKE ALL ON DATABASE " + quoteIdentifier(dbName) + " FROM " + quoteIdentifier(userName));
        } catch (Exception ignored) {
            // REVOKE may fail if DB already dropped
        }
        // Only touch the per-db pool if the database still exists. Calling
        // jdbcFor() on an already-dropped DB would create and cache a pool for a
        // database that no longer exists (P3 leak).
        if (databaseExists(dbName)) {
            try {
                JdbcTemplate target = jdbcFor(dbName);
                target.execute("REVOKE ALL ON SCHEMA public FROM " + quoteIdentifier(userName));
            } catch (Exception ignored) {
                // target DB may already be gone
            }
        }
        jdbcTemplate.execute("DROP ROLE IF EXISTS " + quoteIdentifier(userName));
    }

    public List<String> getUsers(String dbName) {
        return jdbcTemplate.queryForList(
                "SELECT usename FROM pg_user WHERE has_database_privilege(usename, ?, 'CONNECT') AND usename NOT LIKE 'pg_%' ORDER BY usename",
                String.class, dbName);
    }

    // ── pgvector ──────────────────────────────────────────────────────────

    public boolean isVectorAvailable() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_available_extensions WHERE name = 'vector'", Integer.class);
        return c != null && c > 0;
    }

    public boolean isVectorEnabled(String dbName) {
        Integer c = jdbcFor(dbName).queryForObject(
                "SELECT COUNT(*) FROM pg_catalog.pg_extension WHERE extname = 'vector'", Integer.class);
        return c != null && c > 0;
    }

    public void enableVectorExtension(String dbName) {
        // SCHEMA public: with a superuser whose search_path starts with "$user",
        // an unqualified CREATE EXTENSION could land in a $user schema if one
        // exists, leaving public.vector missing for tenant queries.
        jdbcFor(dbName).execute("CREATE EXTENSION IF NOT EXISTS vector SCHEMA public");
    }

    public String vectorVersion(String dbName) {
        try {
            return jdbcFor(dbName).queryForObject(
                    "SELECT extversion FROM pg_catalog.pg_extension WHERE extname = 'vector'", String.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null; // extension not installed in this database
        }
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
