package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.security.PasswordGenerator;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages PgBouncer dynamic config regeneration wired into the Postgres
 * provision/reset/delete lifecycle. Generic — works for any DB provisioned
 * via OmniDB, no hardcoded names.
 *
 * <p>Config files are regenerated under {@code app.pgbouncer.config-dir}
 * (default {@code ./pgbouncer}). Each operation holds
 * {@code DatabaseLockRegistry} per-db lock externally (in ProvisioningService)
 * and this service adds a file-level lock to avoid torn writes between
 * concurrent provisions of different DBs.</p>
 */
@Service
@ConditionalOnProperty(name = "app.pgbouncer.enabled", havingValue = "true")
public class PostgresPgbouncerService {

    private static final Logger log = LoggerFactory.getLogger(PostgresPgbouncerService.class);

    private final PgbouncerProperties properties;
    private final PasswordGenerator passwordGenerator;
    private final ManagedDatabaseStore managedDatabaseStore;
    private final EncryptionService encryptionService;
    private final ReentrantLock fileLock = new ReentrantLock();

    // Effective passwords after auto-generation (never logged)
    private String effectiveAdminPassword;
    private String effectiveStatsPassword;

    @Autowired
    public PostgresPgbouncerService(PgbouncerProperties properties,
                                     PasswordGenerator passwordGenerator,
                                     @Autowired(required = false) ManagedDatabaseStore managedDatabaseStore,
                                     @Autowired(required = false) EncryptionService encryptionService) {
        this.properties = properties;
        this.passwordGenerator = passwordGenerator;
        this.managedDatabaseStore = managedDatabaseStore;
        this.encryptionService = encryptionService;
        this.effectiveAdminPassword = resolvePassword(properties.adminPassword(), "admin");
        this.effectiveStatsPassword = resolvePassword(properties.statsPassword(), "stats");
    }

    private String resolvePassword(String configured, String role) {
        if (!isBlank(configured)) return configured;
        // Try persisted file first for stability across restarts
        Path persisted = Path.of(properties.configDir(), ".pgbouncer-" + role + "-pass");
        try {
            if (Files.exists(persisted)) {
                String saved = Files.readString(persisted, StandardCharsets.UTF_8).trim();
                if (!saved.isBlank()) return saved;
            }
        } catch (IOException ignored) {}
        String generated = passwordGenerator.generate(24);
        try {
            Files.createDirectories(Path.of(properties.configDir()));
            Files.writeString(persisted, generated, StandardCharsets.UTF_8);
            log.warn("PgBouncer {} password auto-generated and persisted to {} (not logged). Set PGBOUNCER_{}_PASSWORD in .env for explicit control.", role, persisted, role.toUpperCase());
        } catch (IOException e) {
            log.warn("PgBouncer {} password auto-generated (could not persist): {}", role, e.getMessage());
        }
        return generated;
    }

    /**
     * Called after a Postgres DB provision or password reset.
     * Regenerates config to include {@code dbName -> host:postgres:5432 dbname=dbName}
     * then RELOADs. Per-DB users authenticate via {@code auth_query} against
     * {@code pg_shadow} (SCRAM), so no per-DB {@code userlist.txt} entries are written.
     */
    public void syncDatabase(String dbName, String userName, String plainPassword) {
        if (isBlank(dbName)) return;
        fileLock.lock();
        try {
            ensureConfigDir();
            regenerateFromStore();
            ensureDatabaseEntry(dbName);
            reload();
            log.info("PgBouncer config synced for database '{}'", dbName);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Called after delete. Removes DB entry then RELOADs.
     */
    public void removeDatabase(String dbName, String userName) {
        if (isBlank(dbName)) return;
        fileLock.lock();
        try {
            ensureConfigDir();
            regenerateFromStore();
            removeDatabaseEntry(dbName);
            reload();
            log.info("PgBouncer config removed for database '{}'", dbName);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Initial reconciliation on startup — rebuild from current store.
     */
    public void reconcileAll() {
        fileLock.lock();
        try {
            ensureConfigDir();
            regenerateFromStore();
            reload();
        } finally {
            fileLock.unlock();
        }
    }

    String effectiveAdminUser() { return properties.adminUser(); }
    String effectiveStatsUser() { return properties.statsUser(); }
    // For tests — do not log
    String effectiveAdminPasswordForTest() { return effectiveAdminPassword; }
    String effectiveStatsPasswordForTest() { return effectiveStatsPassword; }

    private void regenerateFromStore() {
        try {
            Path dir = Path.of(properties.configDir());
            Path ini = dir.resolve("pgbouncer.ini");
            Path userlist = dir.resolve("userlist.txt");

            StringBuilder databases = new StringBuilder();
            databases.append("[databases]\n");
            databases.append("* = host=postgres port=5432 auth_user=postgres\n");
            if (managedDatabaseStore != null) {
                try {
                    var all = managedDatabaseStore.findAllByEngineType(DatabaseEngineType.POSTGRES);
                    for (var md : all) {
                        String db = md.getDbName();
                        if (!isBlank(db)) {
                            databases.append(escapeDbName(db))
                                    .append(" = host=postgres port=5432 dbname=")
                                    .append(escapeDbName(db)).append("\n");
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not list managed Postgres DBs for pgbouncer.ini regeneration", e);
                }
            }

            String iniContent = """
                    ; Generated by PostgresPgbouncerService — do not edit manually (provision/reset/delete regenerates)
                    %s
                    [pgbouncer]
                    listen_port = %d
                    listen_addr = 127.0.0.1
                    auth_type = scram-sha-256
                    auth_file = /etc/pgbouncer/userlist.txt
                    auth_user = postgres
                    pool_mode = %s
                    max_client_conn = %d
                    default_pool_size = %d
                    reserve_pool_size = 5
                    reserve_pool_timeout = 3
                    max_db_connections = 50
                    server_reset_query = DISCARD ALL
                    ignore_startup_parameters = extra_float_digits
                    admin_users = %s
                    stats_users = %s
                    """.formatted(
                    databases.toString().stripTrailing(),
                    properties.port(),
                    properties.poolMode(),
                    properties.maxClientConn(),
                    properties.defaultPoolSize(),
                    properties.adminUser(),
                    properties.statsUser()
            );
            Files.writeString(ini, iniContent, StandardCharsets.UTF_8);

            // userlist.txt — only admin + stats; per-DB users auth via auth_query (SCRAM) against pg_shadow
            StringBuilder ul = new StringBuilder();
            ul.append('"').append(properties.adminUser()).append("\" \"")
                    .append(pgbouncerMd5(properties.adminUser(), effectiveAdminPassword)).append("\"\n");
            ul.append('"').append(properties.statsUser()).append("\" \"")
                    .append(pgbouncerMd5(properties.statsUser(), effectiveStatsPassword)).append("\"\n");
            Files.writeString(userlist, ul.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to regenerate PgBouncer config files in {}", properties.configDir(), e);
        }
    }

    private void ensureDatabaseEntry(String dbName) {
        try {
            Path ini = Path.of(properties.configDir()).resolve("pgbouncer.ini");
            if (!Files.exists(ini)) return;
            String content = Files.readString(ini, StandardCharsets.UTF_8);
            String entry = escapeDbName(dbName) + " = host=postgres port=5432 dbname=" + escapeDbName(dbName);
            if (!content.contains(entry)) {
                // Insert after [databases] wildcard line
                String updated = content.replace("* = host=postgres port=5432 auth_user=postgres",
                        "* = host=postgres port=5432 auth_user=postgres\n" + entry);
                Files.writeString(ini, updated, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Could not ensure pgbouncer.ini database entry for {}", dbName, e);
        }
    }

    private void removeDatabaseEntry(String dbName) {
        try {
            Path ini = Path.of(properties.configDir()).resolve("pgbouncer.ini");
            if (!Files.exists(ini)) return;
            String content = Files.readString(ini, StandardCharsets.UTF_8);
            String target = escapeDbName(dbName) + " = host=postgres port=5432 dbname=" + escapeDbName(dbName);
            String updated = content.replace(target + "\n", "").replace(target, "");
            if (!updated.equals(content)) {
                Files.writeString(ini, updated, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Could not remove pgbouncer.ini entry for {}", dbName, e);
        }
    }

    private void reload() {
        String url = "jdbc:postgresql://127.0.0.1:" + properties.port() + "/pgbouncer?sslmode=disable&connectTimeout=2&socketTimeout=3";
        try (Connection c = DriverManager.getConnection(url, properties.adminUser(), effectiveAdminPassword);
             Statement st = c.createStatement()) {
            st.execute("RELOAD");
            log.debug("PgBouncer RELOAD issued via admin console");
        } catch (Exception e) {
            log.warn("PgBouncer RELOAD failed (container may be stopped or not yet ready): {}", e.getMessage());
        }
    }

    private void ensureConfigDir() {
        try {
            Files.createDirectories(Path.of(properties.configDir()));
        } catch (IOException e) {
            log.warn("Could not create PgBouncer config dir {}", properties.configDir(), e);
        }
    }

    static String pgbouncerMd5(String user, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update((password + user).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return "md5" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private static String escapeDbName(String s) { return s.replace("\"", "\"\""); }
    private static String escapeUser(String s) { return s.replace("\"", "\"\""); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
