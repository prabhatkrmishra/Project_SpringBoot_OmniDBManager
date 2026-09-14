package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * S-12 P3-1: read-only resource reconciliation for operators.
 *
 * <p>Compares Mongo managed-database metadata against live engine catalogs
 * and reports mismatches (missing/orphan/inconsistent resources) as
 * structured data. Diagnostic only: it holds no lifecycle locks, runs no
 * transaction spanning engines, and NEVER mutates anything — no DROP,
 * CREATE, ALTER, metadata writes, or PgBouncer state changes. A resource
 * that disappears mid-scan is reported as observed, never fatal.
 *
 * <p>No tenant passwords, ciphertext, or hashes are read or returned; only
 * names, statuses, and non-sensitive details (owners, privilege flags,
 * grant text of orphan accounts) leave this service.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** Mongo system databases are never tenant resources. */
    private static final Set<String> MONGO_SYSTEM_DATABASES = Set.of("admin", "local", "config");

    private final ManagedDatabaseStore managedDatabaseStore;
    private final Optional<PostgresDatabaseRepository> postgresRepository;
    private final Optional<MysqlDatabaseRepository> mysqlRepository;
    private final Optional<MongoDatabaseRepository> mongoRepository;
    private final Optional<PgbouncerProperties> pgbouncerProperties;

    public ReconciliationService(ManagedDatabaseStore managedDatabaseStore,
                                 @Autowired(required = false) PostgresDatabaseRepository postgresRepository,
                                 @Autowired(required = false) MysqlDatabaseRepository mysqlRepository,
                                 @Autowired(required = false) MongoDatabaseRepository mongoRepository,
                                 @Autowired(required = false) PgbouncerProperties pgbouncerProperties) {
        this.managedDatabaseStore = managedDatabaseStore;
        this.postgresRepository = Optional.ofNullable(postgresRepository);
        this.mysqlRepository = Optional.ofNullable(mysqlRepository);
        this.mongoRepository = Optional.ofNullable(mongoRepository);
        this.pgbouncerProperties = Optional.ofNullable(pgbouncerProperties);
    }

    /** One inspected resource and its verdict. Never carries secrets. */
    public record ResourceEntry(String name, String status, String detail) {
    }

    /** Per-engine diagnostic snapshot. {@code state} is OK or UNAVAILABLE. */
    public record EngineReport(String engine, String state, List<ResourceEntry> resources, String note) {
    }

    /** Whole-fleet diagnostic snapshot. Never throws for engine outages. */
    public record ReconciliationReport(boolean ok, List<EngineReport> engines) {
    }

    /**
     * Reconciles every engine with both metadata access and a live
     * repository. Each engine is isolated in its own failure boundary: one
     * engine's outage is reported, never fatal to the others.
     */
    public ReconciliationReport reconcile() {
        List<EngineReport> engines = new ArrayList<>();
        engines.add(reconcilePostgres());
        engines.add(reconcileMysql());
        engines.add(reconcileMongo());
        boolean ok = engines.stream().allMatch(e -> e.state().equals("OK"));
        return new ReconciliationReport(ok, List.copyOf(engines));
    }

    private List<ManagedDatabase> metadataFor(DatabaseEngineType engine) {
        try {
            return managedDatabaseStore.findAllByEngineType(engine);
        } catch (Exception e) {
            log.warn("Reconciliation metadata read failed for {}", engine, e);
            return null;
        }
    }

    // ── PostgreSQL ────────────────────────────────────────────────────

    private EngineReport reconcilePostgres() {
        if (postgresRepository.isEmpty()) {
            return new EngineReport("POSTGRES", "UNAVAILABLE", List.of(), "postgres repository not configured");
        }
        List<ManagedDatabase> meta = metadataFor(DatabaseEngineType.POSTGRES);
        if (meta == null) {
            return new EngineReport("POSTGRES", "UNAVAILABLE", List.of(), "metadata store unreadable");
        }
        PostgresDatabaseRepository repo = postgresRepository.get();
        Set<String> liveDbs;
        List<PostgresDatabaseRepository.RoleDescriptor> roles;
        try {
            liveDbs = new HashSet<>(repo.listDatabaseNames());
            roles = repo.listRoleDescriptors();
        } catch (Exception e) {
            log.warn("Reconciliation PostgreSQL catalog read failed", e);
            return new EngineReport("POSTGRES", "UNAVAILABLE", List.of(), "postgresql unreachable: " + safeMessage(e));
        }
        Set<String> metaDbNames = new HashSet<>();
        Set<String> metaUsers = new HashSet<>();
        for (ManagedDatabase m : meta) {
            metaDbNames.add(m.getDbName());
            metaUsers.add(m.getUserName());
        }
        java.util.Map<String, PostgresDatabaseRepository.RoleDescriptor> roleByName = new java.util.HashMap<>();
        for (PostgresDatabaseRepository.RoleDescriptor r : roles) {
            roleByName.put(r.name(), r);
        }
        String authUser = pgbouncerProperties.map(p -> PgbouncerProperties.AUTH_USER).orElse("pgbouncer_auth");
        List<ResourceEntry> out = new ArrayList<>();
        for (ManagedDatabase m : meta) {
            if (!liveDbs.contains(m.getDbName())) {
                out.add(new ResourceEntry(m.getDbName(), "MISSING_DATABASE",
                        "metadata exists but database absent in PostgreSQL"));
                continue;
            }
            PostgresDatabaseRepository.RoleDescriptor role = roleByName.get(m.getUserName());
            if (role == null) {
                out.add(new ResourceEntry(m.getDbName(), "MISSING_ROLE",
                        "database present but metadata role '" + m.getUserName() + "' absent"));
                continue;
            }
            Optional<String> owner = repo.databaseOwner(m.getDbName());
            if (owner.isEmpty()) {
                out.add(new ResourceEntry(m.getDbName(), "INCONSISTENT",
                        "database disappeared between catalog reads"));
                continue;
            }
            if (!owner.get().equals(m.getUserName())) {
                out.add(new ResourceEntry(m.getDbName(), "INCONSISTENT",
                        "expected owner '" + m.getUserName() + "' but actual owner '" + owner.get() + "'"));
                continue;
            }
            if (m.isPooled() && !repo.isAuthLookupInstalled(m.getDbName())) {
                out.add(new ResourceEntry(m.getDbName(), "INCONSISTENT",
                        "pooled metadata but pgbouncer.user_lookup function absent"));
                continue;
            }
            out.add(new ResourceEntry(m.getDbName(), "HEALTHY",
                    "database+role present, owner matches" + (m.isPooled() ? ", pooled auth installed" : "")));
        }
        for (String db : liveDbs) {
            if (!metaDbNames.contains(db)) {
                out.add(new ResourceEntry(db, "ORPHAN_DATABASE",
                        "database exists in PostgreSQL without matching metadata; do not delete without establishing ownership"));
            }
        }
        for (PostgresDatabaseRepository.RoleDescriptor r : roles) {
            if (metaUsers.contains(r.name()) || r.name().equals(authUser)) {
                continue;
            }
            if (r.superuser()) {
                out.add(new ResourceEntry(r.name(), "SERVICE_ACCOUNT", "superuser role without metadata; expected for management accounts"));
            } else {
                out.add(new ResourceEntry(r.name(), "ORPHAN_ROLE",
                        "role exists without matching metadata; do not drop without establishing ownership"));
            }
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return new EngineReport("POSTGRES", "OK", List.copyOf(out), "");
    }

    // ── MySQL ─────────────────────────────────────────────────────────

    private EngineReport reconcileMysql() {
        if (mysqlRepository.isEmpty()) {
            return new EngineReport("MYSQL", "UNAVAILABLE", List.of(), "mysql repository not configured");
        }
        List<ManagedDatabase> meta = metadataFor(DatabaseEngineType.MYSQL);
        if (meta == null) {
            return new EngineReport("MYSQL", "UNAVAILABLE", List.of(), "metadata store unreadable");
        }
        MysqlDatabaseRepository repo = mysqlRepository.get();
        Set<String> liveDbs;
        Set<String> liveUsers;
        try {
            liveDbs = new HashSet<>(repo.listDatabaseNames());
            liveUsers = new HashSet<>(repo.listAccountNames());
        } catch (Exception e) {
            log.warn("Reconciliation MySQL catalog read failed", e);
            return new EngineReport("MYSQL", "UNAVAILABLE", List.of(), "mysql unreachable: " + safeMessage(e));
        }
        Set<String> metaDbNames = new HashSet<>();
        Set<String> metaUsers = new HashSet<>();
        for (ManagedDatabase m : meta) {
            metaDbNames.add(m.getDbName());
            metaUsers.add(m.getUserName());
        }
        List<ResourceEntry> out = new ArrayList<>();
        for (ManagedDatabase m : meta) {
            if (!liveDbs.contains(m.getDbName())) {
                out.add(new ResourceEntry(m.getDbName(), "MISSING_DATABASE",
                        "metadata exists but database absent in MySQL"));
                continue;
            }
            if (!liveUsers.contains(m.getUserName())) {
                out.add(new ResourceEntry(m.getDbName(), "MISSING_ROLE",
                        "database present but metadata user '" + m.getUserName() + "' absent (server-global semantics)"));
                continue;
            }
            out.add(new ResourceEntry(m.getDbName(), "HEALTHY", "database+user present"));
        }
        for (String db : liveDbs) {
            if (!metaDbNames.contains(db)) {
                out.add(new ResourceEntry(db, "ORPHAN_DATABASE",
                        "database exists in MySQL without matching metadata; do not delete without establishing ownership"));
            }
        }
        for (String user : liveUsers) {
            if (metaUsers.contains(user)) {
                continue;
            }
            String detail;
            try {
                detail = "grants: " + String.join(" | ", repo.listGrants(user));
            } catch (Exception e) {
                detail = "grants unreadable";
            }
            out.add(new ResourceEntry(user, "ORPHAN_ROLE",
                    "server-global account without matching metadata (" + detail + "); do not drop without establishing ownership"));
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return new EngineReport("MYSQL", "OK", List.copyOf(out), "");
    }

    // ── MongoDB ───────────────────────────────────────────────────────
    //
    // Database-level comparison only. Per-database user enumeration
    // (usersInfo across every database) is privileged, racy, and would turn
    // a diagnostic into an administration surface, so user-level orphan
    // determination is explicitly out of scope; membership/password checks
    // happen at provision/validation time instead.

    private EngineReport reconcileMongo() {
        if (mongoRepository.isEmpty()) {
            return new EngineReport("MONGO", "UNAVAILABLE", List.of(), "mongo repository not configured");
        }
        List<ManagedDatabase> meta = metadataFor(DatabaseEngineType.MONGO);
        if (meta == null) {
            return new EngineReport("MONGO", "UNAVAILABLE", List.of(), "metadata store unreadable");
        }
        MongoDatabaseRepository repo = mongoRepository.get();
        Set<String> liveDbs;
        try {
            liveDbs = new HashSet<>(repo.listDatabaseNames());
            liveDbs.removeAll(MONGO_SYSTEM_DATABASES);
        } catch (Exception e) {
            log.warn("Reconciliation Mongo catalog read failed", e);
            return new EngineReport("MONGO", "UNAVAILABLE", List.of(), "mongo unreachable: " + safeMessage(e));
        }
        Set<String> metaDbNames = new HashSet<>();
        for (ManagedDatabase m : meta) {
            metaDbNames.add(m.getDbName());
        }
        List<ResourceEntry> out = new ArrayList<>();
        for (ManagedDatabase m : meta) {
            if (!liveDbs.contains(m.getDbName())) {
                out.add(new ResourceEntry(m.getDbName(), "MISSING_DATABASE",
                        "metadata exists but database absent in MongoDB"));
            } else {
                out.add(new ResourceEntry(m.getDbName(), "HEALTHY", "database present (user-level check out of scope)"));
            }
        }
        for (String db : liveDbs) {
            if (!metaDbNames.contains(db)) {
                out.add(new ResourceEntry(db, "ORPHAN_DATABASE",
                        "database exists in MongoDB without matching metadata; do not delete without establishing ownership"));
            }
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return new EngineReport("MONGO", "OK", List.copyOf(out),
                "user-level orphan determination intentionally out of scope");
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m.trim();
    }
}
