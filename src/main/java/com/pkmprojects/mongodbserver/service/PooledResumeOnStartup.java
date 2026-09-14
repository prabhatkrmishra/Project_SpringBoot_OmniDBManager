package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * S-09 P2: crash recovery for PgBouncer {@code PAUSE}. Deletion holds
 * {@code PAUSE} on the pooled database while it terminates sessions and
 * drops the resource, releasing it in {@code finally} — but a JVM crash
 * between PAUSE and RESUME leaves the pooler holding that database's
 * clients indefinitely, with nothing in the JVM to release them (the
 * DELETING guard is in-memory only).
 *
 * <p>On startup, best-effort {@code RESUME} every metadata-pooled
 * PostgreSQL database. RESUME on an unpaused or nonexistent database is a
 * harmless pooler-side no-op, so this cannot disrupt healthy state; every
 * failure is logged, never thrown. It deliberately does NOT delete or
 * recreate anything (ownership cannot be proven at startup).
 */
@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PooledResumeOnStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PooledResumeOnStartup.class);

    private final ManagedDatabaseStore managedDatabaseStore;
    private final PgbouncerAdminService pgbouncerAdminService;

    public PooledResumeOnStartup(ManagedDatabaseStore managedDatabaseStore,
                                 PgbouncerAdminService pgbouncerAdminService) {
        this.managedDatabaseStore = managedDatabaseStore;
        this.pgbouncerAdminService = pgbouncerAdminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var pooled = managedDatabaseStore.findAll().stream()
                    .filter(m -> m.getEngineType() == DatabaseEngineType.POSTGRES && m.isPooled())
                    .map(com.pkmprojects.mongodbserver.model.ManagedDatabase::getDbName)
                    .toList();
            for (String dbName : pooled) {
                try {
                    pgbouncerAdminService.resumeDb(dbName);
                } catch (Exception e) {
                    log.warn("Startup RESUME for pooled database '{}' failed (pooler may be down) — continuing", dbName, e);
                }
            }
            if (!pooled.isEmpty()) {
                log.info("Startup pooled-state reconciliation checked {} database(s)", pooled.size());
            }
        } catch (Exception e) {
            log.warn("Startup pooled-state reconciliation failed — continuing without it", e);
        }
    }
}
