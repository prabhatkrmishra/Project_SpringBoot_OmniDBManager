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

    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}
