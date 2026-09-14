package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.security.PasswordGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL accounts ({@code user@'%'}) are server-global like PG
 * roles — a name requested for a second database must not reuse the first
 * tenant's account (shared password + accumulating cross-database grants).
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningServiceMysqlTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Mock private MongoDatabaseRepository mongoRepo;
    @Mock private com.pkmprojects.mongodbserver.store.ManagedDatabaseStore managedRepo;
    @Mock private AuditLogRepository auditRepo;
    @Mock private PasswordGenerator passwordGen;
    @Mock private Environment env;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private MysqlDatabaseRepository mysqlRepo;

    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(env.getProperty("spring.mongodb.uri", "")).thenReturn("mongodb://root:root@localhost:27017/?authSource=admin");
        org.mockito.Mockito.lenient().when(env.getProperty("app.mongo.issued-host", "")).thenReturn("");
        org.mockito.Mockito.lenient().when(env.getProperty("app.mongo.tls", Boolean.class, false)).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MongoDatabaseEngine mongoEngine = new MongoDatabaseEngine(mongoRepo, env);
        MysqlDatabaseEngine mysqlEngine = new MysqlDatabaseEngine(mysqlRepo,
                "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "", false);
        service = new ProvisioningService(mongoRepo, managedRepo,
                new com.pkmprojects.mongodbserver.store.AuditLogRepositoryAdapter(auditRepo),
                new DatabaseNameValidator(),
                passwordGen, Clock.fixed(NOW, ZoneOffset.UTC), env, publisher,
                new DatabaseLockRegistry(), mongoEngine, null, null, mysqlEngine, mysqlRepo, null);
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void secondDatabaseWithSameRequestedUserGetsDistinctUser() {
        when(mysqlRepo.userExists("app")).thenReturn(false);
        DatabaseInfo first = service.provision(
                new CreateDatabaseForm("dbalpha", DatabaseEngineType.MYSQL, "app", "secret111"));
        assertThat(first.connectionString()).contains("app:secret111@");

        when(mysqlRepo.userExists("app")).thenReturn(true);
        when(mysqlRepo.userExists(argThat(s -> s instanceof String str && str.startsWith("omni_"))))
                .thenReturn(false);
        DatabaseInfo second = service.provision(
                new CreateDatabaseForm("dbbeta", DatabaseEngineType.MYSQL, "app", "secret222"));

        ArgumentCaptor<String> users = ArgumentCaptor.forClass(String.class);
        verify(mysqlRepo, times(2)).createUser(any(), users.capture(), any());
        assertThat(users.getAllValues().get(0)).isEqualTo("app");
        String generated = users.getAllValues().get(1);
        assertThat(generated).isNotEqualTo("app");
        assertThat(generated).startsWith("omni_dbbeta_");
        assertThat(generated.length()).isLessThanOrEqualTo(32);
        verify(mysqlRepo).createDatabase("dbbeta");
        verify(mysqlRepo).grantPrivileges("dbbeta", generated);
        assertThat(second.connectionString()).contains(generated + ":secret222@");

        ArgumentCaptor<ManagedDatabase> cap = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(managedRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        ManagedDatabase last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getDbName()).isEqualTo("dbbeta");
        assertThat(last.getUserName()).isEqualTo(generated);
    }

    @Test
    void concurrentSameUserProvisionsGetDistinctUsers() throws Exception {
        java.util.Set<String> liveUsers = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        java.util.concurrent.CyclicBarrier probeBarrier = new java.util.concurrent.CyclicBarrier(2);
        when(mysqlRepo.userExists(any())).thenAnswer(inv -> {
            String user = inv.getArgument(0);
            if (user.equals("app")) {
                try {
                    probeBarrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    probeBarrier.reset();
                }
            }
            return liveUsers.contains(user);
        });
        doAnswer(inv -> {
            liveUsers.add(inv.getArgument(1));
            Thread.sleep(50);
            return null;
        }).when(mysqlRepo).createUser(any(), any(), any());

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var fa = pool.submit(() -> service.provision(
                    new CreateDatabaseForm("dbalpha", DatabaseEngineType.MYSQL, "app", "secret111")));
            var fb = pool.submit(() -> service.provision(
                    new CreateDatabaseForm("dbbeta", DatabaseEngineType.MYSQL, "app", "secret222")));
            DatabaseInfo a = fa.get(60, java.util.concurrent.TimeUnit.SECONDS);
            DatabaseInfo b = fb.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.connectionString()).contains("app:secret111@");
            assertThat(b.connectionString()).doesNotContain("app:secret222@");
            ArgumentCaptor<String> users = ArgumentCaptor.forClass(String.class);
            verify(mysqlRepo, times(2)).createUser(any(), users.capture(), any());
            assertThat(users.getAllValues().get(0)).isEqualTo("app");
            assertThat(users.getAllValues().get(1)).startsWith("omni_").isNotEqualTo("app");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unsafeMysqlPasswordRejectedBeforeAnyLifecycleStep() {
        assertThatThrownBy(() -> service.provision(
                new CreateDatabaseForm("myapp", DatabaseEngineType.MYSQL, "myapp_user", "bad;password1")))
                .isInstanceOf(NameNotAllowedException.class);
        verify(mysqlRepo, never()).createUser(any(), any(), any());
        verify(mysqlRepo, never()).createDatabase(any());
        verify(managedRepo, never()).save(any());
    }

    @Test
    void provisionFailsClosedWhenNoUniqueUserAvailable() {
        when(mysqlRepo.userExists(any())).thenReturn(true);
        assertThatThrownBy(() -> service.provision(
                new CreateDatabaseForm("dbgamma", DatabaseEngineType.MYSQL, "app", "secret333")))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("unique");
        verify(mysqlRepo, never()).createUser(any(), any(), any());
    }

    @Test
    void mysqlValidationFailureFailsProvisionWithCleanup() {
        // Validator-wired MySQL provisions are proven end-to-end
        // like PG ones; a failed tenant login fails closed with cleanup.
        when(mysqlRepo.userExists("app")).thenReturn(false);
        TenantLoginValidationService validator = org.mockito.Mockito.mock(TenantLoginValidationService.class);
        when(validator.validateMysql("dbalpha", "app", "secret111")).thenReturn(false);
        service.setTenantLoginValidationService(validator);

        assertThatThrownBy(() -> service.provision(
                new CreateDatabaseForm("dbalpha", DatabaseEngineType.MYSQL, "app", "secret111")))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not provision database 'dbalpha'")
                .hasStackTraceContaining("MySQL validation failed");
        verify(mysqlRepo).dropDatabase("dbalpha");
        verify(mysqlRepo).dropUser("dbalpha", "app");
    }

    @Test
    void mysqlValidationSuccessProvisions() {
        when(mysqlRepo.userExists("app")).thenReturn(false);
        TenantLoginValidationService validator = org.mockito.Mockito.mock(TenantLoginValidationService.class);
        when(validator.validateMysql("dbalpha", "app", "secret111")).thenReturn(true);
        service.setTenantLoginValidationService(validator);

        DatabaseInfo info = service.provision(
                new CreateDatabaseForm("dbalpha", DatabaseEngineType.MYSQL, "app", "secret111"));

        verify(validator).validateMysql("dbalpha", "app", "secret111");
        assertThat(info.connectionString()).contains("app:secret111@");
    }
}
