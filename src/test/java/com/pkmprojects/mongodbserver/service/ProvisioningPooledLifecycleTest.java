package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.security.PasswordGenerator;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * S-07/S-09: tenant validation wiring + pooled admin-console lifecycle.
 * Validator/admin are setter-injected optionals — absent means legacy behavior.
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningPooledLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Mock private MongoDatabaseRepository mongoRepo;
    @Mock private ManagedDatabaseStore managedRepo;
    @Mock private AuditLogRepository auditRepo;
    @Mock private PasswordGenerator passwordGen;
    @Mock private Environment env;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private PostgresDatabaseRepository postgresRepo;
    @Mock private ConnectionValidationService validator;
    @Mock private PgbouncerAdminService admin;

    private ProvisioningService pooled;

    @BeforeEach
    void setUp() {
        lenient().when(env.getProperty("spring.mongodb.uri", "")).thenReturn("mongodb://root:root@localhost:27017/?authSource=admin");
        lenient().when(env.getProperty("app.mongo.issued-host", "")).thenReturn("");
        lenient().when(env.getProperty("app.mongo.tls", Boolean.class, false)).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var props = new com.pkmprojects.mongodbserver.config.PgbouncerProperties(
                6432, 27432, "transaction", 1000, 5, 2, 3, 10, "admin", "stats", "authsecret");
        var engine = new PostgresDatabaseEngine(postgresRepo, env,
                "jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, "require", props);
        pooled = new ProvisioningService(mongoRepo, managedRepo, auditRepo, new DatabaseNameValidator(),
                passwordGen, Clock.fixed(NOW, ZoneOffset.UTC), env, publisher,
                new DatabaseLockRegistry(), new MongoDatabaseEngine(mongoRepo, env), engine, postgresRepo, null);
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private ManagedDatabase pooledMetadata() {
        ManagedDatabase md = new ManagedDatabase("myapp", DatabaseEngineType.POSTGRES, "myapp_user",
                List.of("CONNECT:myapp"), NOW, NOW, null);
        md.setStoredPassword("mypass");
        md.setPooled(true);
        return md;
    }

    @Test
    void provisionPooledValidatesTenantLoginWhenValidatorWired() {
        when(passwordGen.generate(16)).thenReturn("generatedPass123");
        when(validator.validatePooledDetailed(eq("myapp"), eq("myapp_user"), eq("generatedPass123")))
                .thenReturn(new ConnectionValidationService.PooledValidation(true,
                        ConnectionValidationService.ValidationPath.LOOPBACK));
        pooled.setConnectionValidationService(validator);

        pooled.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "", Boolean.TRUE));

        verify(validator).validatePooledDetailed("myapp", "myapp_user", "generatedPass123");
        verify(managedRepo).save(any(ManagedDatabase.class));
    }

    @Test
    void provisionPooledFailsAndCleansUpWhenValidationFails() {
        when(passwordGen.generate(16)).thenReturn("generatedPass123");
        when(validator.validatePooledDetailed(any(), any(), any()))
                .thenReturn(new ConnectionValidationService.PooledValidation(false,
                        ConnectionValidationService.ValidationPath.NONE));
        pooled.setConnectionValidationService(validator);

        assertThatThrownBy(() -> pooled.provision(
                new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "", Boolean.TRUE)))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not provision database 'myapp'")
                .hasStackTraceContaining("Pooled validation failed");
        verify(postgresRepo).dropDatabase("myapp");
        verify(postgresRepo).dropUser("myapp", "myapp_user");
        verify(managedRepo, never()).save(any());
    }

    @Test
    void repairValidatesAfterInstallAndFailsLoudly() {
        when(postgresRepo.databaseExists("myapp")).thenReturn(true);
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp"))
                .thenReturn(Optional.of(pooledMetadata()));
        when(postgresRepo.isAuthLookupInstalled("myapp")).thenReturn(false);
        when(validator.validatePooledDetailed("myapp", "myapp_user", "mypass"))
                .thenReturn(new ConnectionValidationService.PooledValidation(false,
                        ConnectionValidationService.ValidationPath.NONE));
        pooled.setConnectionValidationService(validator);

        assertThatThrownBy(() -> pooled.repairPooledAuth(DatabaseEngineType.POSTGRES, "myapp"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("pooled validation failed");
        verify(postgresRepo).installAuthLookup("myapp", "pgbouncer_auth");
    }

    @Test
    void deletePooledPausesAndResumesAroundDrop() {
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp"))
                .thenReturn(Optional.of(pooledMetadata()));
        when(admin.pauseDb("myapp")).thenReturn(true);
        pooled.setPgbouncerAdminService(admin);

        pooled.delete(DatabaseEngineType.POSTGRES, "myapp");

        var order = inOrder(admin, postgresRepo);
        order.verify(admin).pauseDb("myapp");
        order.verify(postgresRepo).dropDatabase("myapp");
        order.verify(admin).resumeDb("myapp");
    }

    @Test
    void deletePooledDropFailurePreservesRoleMetadataAndStillResumes() {
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp"))
                .thenReturn(Optional.of(pooledMetadata()));
        doThrow(new RuntimeException("drop failed")).when(postgresRepo).dropDatabase("myapp");
        when(admin.pauseDb("myapp")).thenReturn(true);
        pooled.setPgbouncerAdminService(admin);

        assertThatThrownBy(() -> pooled.delete(DatabaseEngineType.POSTGRES, "myapp"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("preserved for retry");
        verify(postgresRepo, never()).dropUser(any(), any());
        verify(managedRepo, never()).deleteByEngineTypeAndDbName(any(), any());
        // RESUME unconditional even on the failure arm — held clients fail clean, never hang.
        verify(admin).pauseDb("myapp");
        verify(admin).resumeDb("myapp");
    }

    @Test
    void deleteDirectNeverTouchesPooler() {
        ManagedDatabase md = pooledMetadata();
        md.setPooled(false);
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp"))
                .thenReturn(Optional.of(md));
        pooled.setPgbouncerAdminService(admin);

        pooled.delete(DatabaseEngineType.POSTGRES, "myapp");

        verify(admin, never()).pauseDb(any());
        verify(admin, never()).resumeDb(any());
        verify(postgresRepo).dropDatabase("myapp");
    }

    @Test
    void resetPasswordPooledReconnects() {
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp"))
                .thenReturn(Optional.of(pooledMetadata()));
        when(admin.reconnectDb("myapp")).thenReturn(true);
        when(validator.validatePooledDetailed(eq("myapp"), eq("myapp_user"), any()))
                .thenReturn(new ConnectionValidationService.PooledValidation(true,
                        ConnectionValidationService.ValidationPath.PUBLIC));
        pooled.setPgbouncerAdminService(admin);
        pooled.setConnectionValidationService(validator);

        pooled.resetPassword(DatabaseEngineType.POSTGRES, "myapp", new ResetPasswordForm("newpass123"));

        verify(postgresRepo).updateUserPassword("myapp", "myapp_user", "newpass123");
        verify(admin).reconnectDb("myapp");
    }

    @Test
    void resetPasswordDirectSkipsReconnect() {
        ManagedDatabase md = pooledMetadata();
        md.setPooled(false);
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp"))
                .thenReturn(Optional.of(md));
        pooled.setPgbouncerAdminService(admin);

        pooled.resetPassword(DatabaseEngineType.POSTGRES, "myapp", new ResetPasswordForm("newpass123"));

        verify(admin, never()).reconnectDb(any());
    }
}
