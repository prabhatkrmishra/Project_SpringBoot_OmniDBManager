package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S-12 P3-1: read-only reconciliation. Every test also guards the critical
 * safety rule — no DROP/CREATE/ALTER/metadata-write/PgBouncer call may ever
 * happen here (verified explicitly on the happy path).
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private ManagedDatabaseStore store;
    @Mock private PostgresDatabaseRepository pgRepo;
    @Mock private MysqlDatabaseRepository myRepo;
    @Mock private MongoDatabaseRepository mongoRepo;

    private ReconciliationService service() {
        return new ReconciliationService(store, pgRepo, myRepo, mongoRepo, null);
    }

    private static ManagedDatabase md(String db, DatabaseEngineType engine, String user, boolean pooled) {
        ManagedDatabase m = new ManagedDatabase(db, engine, user, List.of(), Instant.now(), Instant.now(), null);
        m.setPooled(pooled);
        return m;
    }

    private void stubEmpty() {
        when(store.findAllByEngineType(any())).thenReturn(List.of());
        when(pgRepo.listDatabaseNames()).thenReturn(List.of());
        when(pgRepo.listRoleDescriptors()).thenReturn(List.of());
        when(myRepo.listDatabaseNames()).thenReturn(List.of());
        when(myRepo.listAccountNames()).thenReturn(List.of());
        when(mongoRepo.listDatabaseNames()).thenReturn(List.of());
    }

    private static ReconciliationService.EngineReport engineOf(ReconciliationService.ReconciliationReport r, String engine) {
        return r.engines().stream().filter(e -> e.engine().equals(engine)).findFirst().orElseThrow();
    }

    private static boolean has(ReconciliationService.EngineReport e, String name, String status) {
        return e.resources().stream().anyMatch(r -> r.name().equals(name) && r.status().equals(status));
    }

    @Test
    void allHealthyAcrossEngines() {
        when(store.findAllByEngineType(DatabaseEngineType.POSTGRES))
                .thenReturn(List.of(md("a", DatabaseEngineType.POSTGRES, "ua", true)));
        when(store.findAllByEngineType(DatabaseEngineType.MYSQL))
                .thenReturn(List.of(md("m", DatabaseEngineType.MYSQL, "um", false)));
        when(store.findAllByEngineType(DatabaseEngineType.MONGO))
                .thenReturn(List.of(md("g", DatabaseEngineType.MONGO, "ug", false)));
        when(pgRepo.listDatabaseNames()).thenReturn(List.of("a"));
        when(pgRepo.listRoleDescriptors()).thenReturn(List.of(
                new PostgresDatabaseRepository.RoleDescriptor("ua", false, true),
                new PostgresDatabaseRepository.RoleDescriptor("root", true, true),
                new PostgresDatabaseRepository.RoleDescriptor("pgbouncer_auth", false, true)));
        when(pgRepo.databaseOwner("a")).thenReturn(Optional.of("ua"));
        when(pgRepo.isAuthLookupInstalled("a")).thenReturn(true);
        when(myRepo.listDatabaseNames()).thenReturn(List.of("m"));
        when(myRepo.listAccountNames()).thenReturn(List.of("um"));
        when(mongoRepo.listDatabaseNames()).thenReturn(List.of("g", "admin", "local"));

        ReconciliationService.ReconciliationReport r = service().reconcile();

        assertThat(r.ok()).isTrue();
        assertThat(has(engineOf(r, "POSTGRES"), "a", "HEALTHY")).isTrue();
        assertThat(has(engineOf(r, "MYSQL"), "m", "HEALTHY")).isTrue();
        assertThat(has(engineOf(r, "MONGO"), "g", "HEALTHY")).isTrue();
        // Service accounts are classified, never flagged as orphans.
        assertThat(has(engineOf(r, "POSTGRES"), "root", "SERVICE_ACCOUNT")).isTrue();
        // No destructive or mutating call anywhere on the happy path.
        verify(pgRepo, never()).dropDatabase(any());
        verify(pgRepo, never()).dropUser(any(), any());
        verify(pgRepo, never()).createUser(any(), any(), any());
        verify(pgRepo, never()).createDatabase(any(), any());
        verify(myRepo, never()).dropDatabase(any());
        verify(myRepo, never()).dropUser(any(), any());
        verify(myRepo, never()).createUser(any(), any(), any());
        verify(mongoRepo, never()).dropDatabase(any());
        verify(mongoRepo, never()).dropUser(any(), any());
        verify(mongoRepo, never()).createUser(any(), any(), any());
        verify(store, never()).save(any());
        // No secrets in any output field.
        String dumped = r.toString();
        assertThat(dumped).doesNotContain("ENC:v1:");
    }

    @Test
    void postgresMissingOrphanAndInconsistent() {
        when(store.findAllByEngineType(any())).thenAnswer(inv -> {
            DatabaseEngineType e = inv.getArgument(0);
            return e == DatabaseEngineType.POSTGRES
                    ? List.of(md("gone", DatabaseEngineType.POSTGRES, "ugone", false),
                            md("norole", DatabaseEngineType.POSTGRES, "norole", false),
                            md("wrongowner", DatabaseEngineType.POSTGRES, "claimed", false),
                            md("nopooler", DatabaseEngineType.POSTGRES, "up", true))
                    : List.of();
        });
        when(pgRepo.listDatabaseNames()).thenReturn(List.of("norole", "wrongowner", "nopooler", "straydb"));
        when(pgRepo.listRoleDescriptors()).thenReturn(List.of(
                new PostgresDatabaseRepository.RoleDescriptor("claimed", false, true),
                new PostgresDatabaseRepository.RoleDescriptor("up", false, true),
                new PostgresDatabaseRepository.RoleDescriptor("strayrole", false, true)));
        when(pgRepo.databaseOwner("wrongowner")).thenReturn(Optional.of("actualowner"));
        when(pgRepo.databaseOwner("nopooler")).thenReturn(Optional.of("up"));
        when(pgRepo.isAuthLookupInstalled("nopooler")).thenReturn(false);
        when(myRepo.listDatabaseNames()).thenReturn(List.of());
        when(myRepo.listAccountNames()).thenReturn(List.of());
        when(mongoRepo.listDatabaseNames()).thenReturn(List.of());

        ReconciliationService.EngineReport pg = engineOf(service().reconcile(), "POSTGRES");

        assertThat(has(pg, "gone", "MISSING_DATABASE")).isTrue();
        assertThat(has(pg, "norole", "MISSING_ROLE")).isTrue();
        assertThat(has(pg, "wrongowner", "INCONSISTENT")).isTrue();
        assertThat(has(pg, "nopooler", "INCONSISTENT")).isTrue();
        assertThat(has(pg, "straydb", "ORPHAN_DATABASE")).isTrue();
        assertThat(has(pg, "strayrole", "ORPHAN_ROLE")).isTrue();
        // ugone/norole-role absent from role list: only reported once via the DB entry.
        assertThat(pg.resources().stream().filter(x -> x.name().equals("ugone")).count()).isEqualTo(0);
    }

    @Test
    void disappearingResourceReportedNotFatal() {
        when(store.findAllByEngineType(DatabaseEngineType.POSTGRES))
                .thenReturn(List.of(md("a", DatabaseEngineType.POSTGRES, "ua", false)));
        when(store.findAllByEngineType(DatabaseEngineType.MYSQL)).thenReturn(List.of());
        when(store.findAllByEngineType(DatabaseEngineType.MONGO)).thenReturn(List.of());
        when(pgRepo.listDatabaseNames()).thenReturn(List.of("a"));
        when(pgRepo.listRoleDescriptors()).thenReturn(List.of(
                new PostgresDatabaseRepository.RoleDescriptor("ua", false, true)));
        // Owner lookup races a concurrent DROP: reported, whole scan survives.
        when(pgRepo.databaseOwner("a")).thenReturn(Optional.empty());
        when(myRepo.listDatabaseNames()).thenReturn(List.of());
        when(myRepo.listAccountNames()).thenReturn(List.of());
        when(mongoRepo.listDatabaseNames()).thenReturn(List.of());

        ReconciliationService.EngineReport pg = engineOf(service().reconcile(), "POSTGRES");

        assertThat(pg.state()).isEqualTo("OK");
        assertThat(has(pg, "a", "INCONSISTENT")).isTrue();
    }

    @Test
    void engineOutageIsolatedPerEngine() {
        when(store.findAllByEngineType(DatabaseEngineType.POSTGRES))
                .thenReturn(List.of(md("a", DatabaseEngineType.POSTGRES, "ua", false)));
        when(store.findAllByEngineType(DatabaseEngineType.MYSQL)).thenReturn(List.of());
        when(store.findAllByEngineType(DatabaseEngineType.MONGO)).thenReturn(List.of());
        when(pgRepo.listDatabaseNames()).thenThrow(new RuntimeException("connection refused"));
        when(myRepo.listDatabaseNames()).thenReturn(List.of());
        when(myRepo.listAccountNames()).thenReturn(List.of());
        when(mongoRepo.listDatabaseNames()).thenReturn(List.of());

        ReconciliationService.ReconciliationReport r = service().reconcile();

        assertThat(r.ok()).isFalse();
        assertThat(engineOf(r, "POSTGRES").state()).isEqualTo("UNAVAILABLE");
        assertThat(engineOf(r, "MYSQL").state()).isEqualTo("OK");
        assertThat(engineOf(r, "MONGO").state()).isEqualTo("OK");
    }

    @Test
    void mysqlGlobalUserSemantics() {
        when(store.findAllByEngineType(DatabaseEngineType.MYSQL)).thenReturn(List.of(
                md("m1", DatabaseEngineType.MYSQL, "shared", false),
                md("m2", DatabaseEngineType.MYSQL, "gone", false)));
        when(store.findAllByEngineType(DatabaseEngineType.POSTGRES)).thenReturn(List.of());
        when(store.findAllByEngineType(DatabaseEngineType.MONGO)).thenReturn(List.of());
        when(myRepo.listDatabaseNames()).thenReturn(List.of("m1", "m2", "stray"));
        // Server-global: one account serves both metadata rows; stray has grants.
        when(myRepo.listAccountNames()).thenReturn(List.of("shared", "strayu"));
        when(myRepo.listGrants("strayu")).thenReturn(List.of("GRANT ALL ON `stray`.* TO 'strayu'@'%'"));
        when(pgRepo.listDatabaseNames()).thenReturn(List.of());
        when(pgRepo.listRoleDescriptors()).thenReturn(List.of());
        when(mongoRepo.listDatabaseNames()).thenReturn(List.of());

        ReconciliationService.EngineReport my = engineOf(service().reconcile(), "MYSQL");

        assertThat(has(my, "m1", "HEALTHY")).isTrue();
        assertThat(has(my, "m2", "MISSING_ROLE")).isTrue();
        assertThat(has(my, "stray", "ORPHAN_DATABASE")).isTrue();
        assertThat(my.resources().stream()
                .anyMatch(x -> x.name().equals("strayu") && x.status().equals("ORPHAN_ROLE")
                        && x.detail().contains("GRANT ALL"))).isTrue();
    }

    @Test
    void mongoDatabaseLevelWithDocumentedUserLimitation() {
        when(store.findAllByEngineType(DatabaseEngineType.MONGO)).thenReturn(List.of(
                md("g", DatabaseEngineType.MONGO, "ug", false),
                md("vanished", DatabaseEngineType.MONGO, "uv", false)));
        when(store.findAllByEngineType(DatabaseEngineType.POSTGRES)).thenReturn(List.of());
        when(store.findAllByEngineType(DatabaseEngineType.MYSQL)).thenReturn(List.of());
        when(mongoRepo.listDatabaseNames()).thenReturn(List.of("g", "stray", "admin", "config"));
        when(pgRepo.listDatabaseNames()).thenReturn(List.of());
        when(pgRepo.listRoleDescriptors()).thenReturn(List.of());
        when(myRepo.listDatabaseNames()).thenReturn(List.of());
        when(myRepo.listAccountNames()).thenReturn(List.of());

        ReconciliationService.EngineReport mo = engineOf(service().reconcile(), "MONGO");

        assertThat(has(mo, "g", "HEALTHY")).isTrue();
        assertThat(has(mo, "vanished", "MISSING_DATABASE")).isTrue();
        assertThat(has(mo, "stray", "ORPHAN_DATABASE")).isTrue();
        // System databases never surface.
        assertThat(mo.resources().stream().anyMatch(x -> x.name().equals("admin"))).isFalse();
        assertThat(mo.note()).contains("user-level");
    }

    @Test
    void emptyEnvironmentIsHealthy() {
        stubEmpty();
        ReconciliationService.ReconciliationReport r = service().reconcile();
        assertThat(r.ok()).isTrue();
        assertThat(r.engines()).allMatch(e -> e.resources().isEmpty());
    }
}
