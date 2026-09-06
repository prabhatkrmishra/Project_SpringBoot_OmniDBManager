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
import java.util.List;
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
     * and the md5-hashed user entry, then RELOADs.
     */
    public void syncDatabase(String dbName, String userName, String plainPassword) {
        if (isBlank(dbName) || isBlank(userName) || plainPassword == null) return;
        fileLock.lock();
        try {
            ensureConfigDir();
            regenerateFromStore();
            // Ensure the caller’s entry is present even if store not yet flushed
            upsertUserInFile(userName, plainPassword);
            ensureDatabaseEntry(dbName);
            reload();
            log.info("PgBouncer config synced for database '{}' / user '{}'", dbName, userName);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Called after delete. Removes DB and optionally user entry, then RELOADs.
     */
    public void removeDatabase(String dbName, String userName) {
        if (isBlank(dbName)) return;
        fileLock.lock();
        try {
            ensureConfigDir();
            regenerateFromStore();
            // Remove per-db section even if store already deleted
            removeDatabaseEntry(dbName);
            if (!isBlank(userName)) {
                // Only remove user if no other DB still uses it
                boolean stillUsed = isUserStillUsed(userName);
                if (!stillUsed) {
                    removeUserFromFile(userName);
                }
            }
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

            // userlist.txt — admin + stats + per-db users (hashed from store if possible)
            StringBuilder ul = new StringBuilder();
            ul.append('"').append(properties.adminUser()).append("\" \"")
                    .append(pgbouncerMd5(properties.adminUser(), effectiveAdminPassword)).append("\"\n");
            ul.append('"').append(properties.statsUser()).append("\" \"")
                    .append(pgbouncerMd5(properties.statsUser(), effectiveStatsPassword)).append("\"\n");
            if (managedDatabaseStore != null) {
                try {
                    var all = managedDatabaseStore.findAllByEngineType(DatabaseEngineType.POSTGRES);
                    for (var md : all) {
                        String user = md.getUserName();
                        String enc = md.getStoredPassword();
                        if (isBlank(user) || enc == null) continue;
                        // Try to decrypt to get plain for hashing; if decrypt fails, skip (hashed from syncDatabase will cover recent provisions)
                        String plain = tryDecrypt(enc);
                        if (plain != null && !plain.isBlank()) {
                            ul.append('"').append(escapeUser(user)).append("\" \"")
                                    .append(pgbouncerMd5(user, plain)).append("\"\n");
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not regenerate pgbouncer userlist from store", e);
                }
            }
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

    private void upsertUserInFile(String user, String plainPassword) {
        try {
            Path ul = Path.of(properties.configDir()).resolve("userlist.txt");
            if (!Files.exists(ul)) return;
            List<String> lines = Files.readAllLines(ul, StandardCharsets.UTF_8);
            String hashed = pgbouncerMd5(user, plainPassword);
            String wanted = "\"" + escapeUser(user) + "\" \"" + hashed + "\"";
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("\"" + escapeUser(user) + "\"")) {
                    lines.set(i, wanted);
                    found = true;
                    break;
                }
            }
            if (!found) lines.add(wanted);
            Files.write(ul, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not upsert PgBouncer userlist entry for {}", user, e);
        }
    }

    private void removeUserFromFile(String user) {
        try {
            Path ul = Path.of(properties.configDir()).resolve("userlist.txt");
            if (!Files.exists(ul)) return;
            List<String> lines = Files.readAllLines(ul, StandardCharsets.UTF_8);
            lines.removeIf(l -> l.contains("\"" + escapeUser(user) + "\""));
            Files.write(ul, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not remove PgBouncer userlist entry for {}", user, e);
        }
    }

    private boolean isUserStillUsed(String user) {
        if (managedDatabaseStore == null) return false;
        try {
            var all = managedDatabaseStore.findAllByEngineType(DatabaseEngineType.POSTGRES);
            return all.stream().anyMatch(md -> user.equals(md.getUserName()));
        } catch (Exception e) {
            return false;
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

    private String tryDecrypt(String stored) {
        if (stored == null) return null;
        if (stored.startsWith("ENC:v1:")) {
            if (encryptionService != null) {
                try {
                    return encryptionService.decrypt(stored);
                } catch (Exception e) {
                    log.warn("Could not decrypt stored password for PgBouncer userlist (will be re-synced on next provision/reset): {}", e.getMessage());
                    return null;
                }
            }
            return null;
        }
        return stored;
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
