package com.pkmprojects.mongodbserver.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
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
    private final DataSource dataSource;
    private final String postgresUri;
    private final String username;
    private final String password;

    public PostgresDatabaseRepository(JdbcTemplate jdbcTemplate,
                                      DataSource dataSource,
                                      @Value("${app.postgres.uri:jdbc:postgresql://127.0.0.1:9813/postgres}") String postgresUri,
                                      @Value("${POSTGRES_ROOT_USER:root}") String username,
                                      @Value("${POSTGRES_ROOT_PASSWORD:root}") String password) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.postgresUri = postgresUri;
        this.username = username;
        this.password = password;
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
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
}
