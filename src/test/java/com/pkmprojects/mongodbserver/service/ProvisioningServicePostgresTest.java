package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProvisioningServicePostgresTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Mock private MongoDatabaseRepository mongoRepo;
    @Mock private ManagedDatabaseRepository managedRepo;
    @Mock private AuditLogRepository auditRepo;
    @Mock private PasswordGenerator passwordGen;
    @Mock private Environment env;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private PostgresDatabaseRepository postgresRepo;

    private ProvisioningService service;
    private PostgresDatabaseEngine postgresEngine;

    @BeforeEach
    void setUp() {
        lenient().when(env.getProperty("spring.mongodb.uri", "")).thenReturn("mongodb://root:root@localhost:27017/?authSource=admin");
        lenient().when(env.getProperty("app.mongo-public-host", "")).thenReturn("");
        lenient().when(env.getProperty("app.mongo-public-tls", Boolean.class, false)).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MongoDatabaseEngine mongoEngine = new MongoDatabaseEngine(mongoRepo, env);
        postgresEngine = new PostgresDatabaseEngine(postgresRepo, env,
                "jdbc:postgresql://127.0.0.1:9813/postgres", "", false, "require");
        service = new ProvisioningService(mongoRepo, managedRepo, auditRepo, new DatabaseNameValidator(),
                passwordGen, Clock.fixed(NOW, ZoneOffset.UTC), env, publisher,
                new DatabaseLockRegistry(), mongoEngine, postgresEngine, postgresRepo, null);
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    // ── provision POSTGRES ──────────────────────────────────────────

    @Test
    void provisionPostgresCreatesUserDatabaseAndGrants() {
        when(passwordGen.generate(16)).thenReturn("generatedPass123");

        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", ""));

        verify(postgresRepo).createUser("myapp", "myapp_user", "generatedPass123");
        verify(postgresRepo).createDatabase("myapp", "myapp_user");
        verify(postgresRepo).grantPrivileges("myapp", "myapp_user");
        ArgumentCaptor<ManagedDatabase> cap = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(managedRepo).save(cap.capture());
        assertThat(cap.getValue().getEngineType()).isEqualTo(DatabaseEngineType.POSTGRES);
        assertThat(cap.getValue().getDbName()).isEqualTo("myapp");
        assertThat(cap.getValue().getStoredPassword()).isEqualTo("generatedPass123");
        assertThat(info.connectionString()).contains("postgresql://");
        assertThat(info.connectionString()).contains("myapp_user:generatedPass123@");
    }

    @Test
    void provisionPostgresWithExplicitPassword() {
        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "mysecret123"));
        verify(postgresRepo).createUser("myapp", "myapp_user", "mysecret123");
        assertThat(info.connectionString()).contains("myapp_user:mysecret123@");
    }

    @Test
    void provisionPostgresEncodesSpecialCharsInConnectionString() {
        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "p@ss#word"));
        assertThat(info.connectionString()).isEqualTo("postgresql://myapp_user:p%40ss%23word@127.0.0.1:9813/myapp?application_name=omnidb");
    }

    @Test
    void provisionPostgresEncodesPercentAndColonInPassword() {
        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "s3cret%#@:"));
        assertThat(info.connectionString()).contains("s3cret%25%23%40%3A");
    }

    @Test
    void provisionPostgresRejectsUppercaseDbName() {
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("MyApp", DatabaseEngineType.POSTGRES, "myapp_user", "")))
                .isInstanceOf(NameNotAllowedException.class);
        verify(postgresRepo, never()).createUser(any(), any(), any());
    }

    @Test
    void provisionPostgresRejectsUppercaseUserName() {
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "MyUser", "")))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void provisionPostgresRejectsSystemDatabase() {
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("postgres", DatabaseEngineType.POSTGRES, "myapp_user", "")))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void provisionPostgresRejectsExistingDatabase() {
        when(postgresRepo.databaseExists("myapp")).thenReturn(true);
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
    }

    @Test
    void provisionPostgresRejectsExistingMetadata() {
        when(managedRepo.existsByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(true);
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
    }

    @Test
    void provisionPostgresCleansUpOnCreateDatabaseFailure() {
        doThrow(new RuntimeException("db create failed")).when(postgresRepo).createDatabase("myapp", "myapp_user");
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "mysecret123")))
                .isInstanceOf(ProvisioningException.class);
        verify(postgresRepo).dropUser("myapp", "myapp_user");
        verify(managedRepo, never()).save(any());
    }

    @Test
    void provisionPostgresCleansUpBothOnGrantFailure() {
        doThrow(new RuntimeException("grant failed")).when(postgresRepo).grantPrivileges("myapp", "myapp_user");
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "mysecret123")))
                .isInstanceOf(ProvisioningException.class);
        verify(postgresRepo).dropDatabase("myapp");
        verify(postgresRepo).dropUser("myapp", "myapp_user");
    }

    @Test
    void provisionPostgresCreateUserFailureThrowsAndDoesNotSave() {
        doThrow(new RuntimeException("create user failed")).when(postgresRepo).createUser("myapp", "myapp_user", "mysecret123");
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "mysecret123")))
                .isInstanceOf(ProvisioningException.class);
        verify(managedRepo, never()).save(any());
        verify(postgresRepo, never()).createDatabase(any(), any());
    }

    @Test
    void provisionPostgresRejectsShortPassword() {
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "short")))
                .isInstanceOf(NameNotAllowedException.class);
        verify(postgresRepo, never()).createUser(any(), any(), any());
    }

    @Test
    void provisionPostgresWithTlsIncludesSslmodeInConnectionString() {
        PostgresDatabaseEngine tlsEngine = new PostgresDatabaseEngine(postgresRepo, env,
                "jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com:5432", true, "require");
        ProvisioningService tlsService = new ProvisioningService(mongoRepo, managedRepo, auditRepo, new DatabaseNameValidator(),
                passwordGen, Clock.fixed(NOW, ZoneOffset.UTC), env, publisher,
                new DatabaseLockRegistry(), new MongoDatabaseEngine(mongoRepo, env), tlsEngine, postgresRepo, null);
        DatabaseInfo info = tlsService.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "mysecret123"));
        assertThat(info.connectionString()).contains("pg.example.com:5432");
        assertThat(info.connectionString()).contains("sslmode=require");
        assertThat(info.connectionString()).contains("application_name=omnidb");
    }

    @Test
    void provisionPostgresWithEncryptionStoresEncryptedPassword() {
        EncryptionService enc = new EncryptionService(new com.pkmprojects.mongodbserver.config.EncryptionProperties(
                java.util.Base64.getEncoder().encodeToString(new byte[32])));
        ProvisioningService encService = new ProvisioningService(mongoRepo, managedRepo, auditRepo, new DatabaseNameValidator(),
                passwordGen, Clock.fixed(NOW, ZoneOffset.UTC), env, publisher,
                new DatabaseLockRegistry(), new MongoDatabaseEngine(mongoRepo, env), postgresEngine, postgresRepo, enc);
        DatabaseInfo info = encService.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "mysecret123"));
        ArgumentCaptor<ManagedDatabase> cap = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(managedRepo).save(cap.capture());
        assertThat(cap.getValue().getStoredPassword()).startsWith("ENC:v1:");
        assertThat(cap.getValue().getStoredPassword()).isNotEqualTo("mysecret123");
        assertThat(info.connectionString()).contains("mysecret123");
    }

    // ── resetPassword POSTGRES ──────────────────────────────────────

    @Test
    void resetPasswordPostgresRotatesAndReturnsConnectionString() {
        ManagedDatabase md = new ManagedDatabase("myapp", DatabaseEngineType.POSTGRES, "myapp_user", List.of("CONNECT:myapp"), NOW, NOW, null);
        md.setStoredPassword("oldpass");
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(Optional.of(md));

        DatabaseInfo info = service.resetPassword(DatabaseEngineType.POSTGRES, "myapp", new ResetPasswordForm("newsecret456"));

        verify(postgresRepo).updateUserPassword("myapp", "myapp_user", "newsecret456");
        assertThat(md.getStoredPassword()).isEqualTo("newsecret456");
        assertThat(info.connectionString()).contains("newsecret456");
        verify(managedRepo).save(md);
    }

    @Test
    void resetPasswordPostgresGeneratesWhenBlank() {
        ManagedDatabase md = new ManagedDatabase("myapp", DatabaseEngineType.POSTGRES, "myapp_user", List.of("CONNECT:myapp"), NOW, NOW, null);
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(Optional.of(md));
        when(passwordGen.generate(16)).thenReturn("rotatedPass456");

        service.resetPassword(DatabaseEngineType.POSTGRES, "myapp", new ResetPasswordForm(""));

        verify(postgresRepo).updateUserPassword("myapp", "myapp_user", "rotatedPass456");
    }

    @Test
    void resetPasswordPostgresThrowsWhenNotProvisioned() {
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resetPassword(DatabaseEngineType.POSTGRES, "myapp", new ResetPasswordForm("newpass123")))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    // ── delete POSTGRES ─────────────────────────────────────────────

    @Test
    void deletePostgresDropsDatabaseAndRole() {
        ManagedDatabase md = new ManagedDatabase("myapp", DatabaseEngineType.POSTGRES, "myapp_user", List.of("CONNECT:myapp"), NOW, NOW, null);
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(Optional.of(md));

        service.delete(DatabaseEngineType.POSTGRES, "myapp");

        verify(postgresRepo).dropDatabase("myapp");
        verify(postgresRepo).dropUser("myapp", "myapp_user");
        verify(managedRepo).deleteByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp");
    }

    @Test
    void deletePostgresWithoutMetadataStillDropsDatabase() {
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(Optional.empty());

        service.delete(DatabaseEngineType.POSTGRES, "myapp");

        verify(postgresRepo).dropDatabase("myapp");
        verify(postgresRepo, never()).dropUser(any(), any());
        verify(managedRepo, never()).deleteByEngineTypeAndDbName(any(), any());
    }

    @Test
    void deletePostgresToleratesDropFailure() {
        ManagedDatabase md = new ManagedDatabase("myapp", DatabaseEngineType.POSTGRES, "myapp_user", List.of("CONNECT:myapp"), NOW, NOW, null);
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(Optional.of(md));
        doThrow(new RuntimeException("drop failed")).when(postgresRepo).dropDatabase("myapp");

        // should not throw — PG delete is best-effort
        service.delete(DatabaseEngineType.POSTGRES, "myapp");

        verify(managedRepo).deleteByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp");
    }

    // ── listDatabases POSTGRES ──────────────────────────────────────

    @Test
    void listDatabasesPostgresExcludesSystemDbs() {
        when(postgresRepo.listDatabaseNames()).thenReturn(List.of("postgres", "template0", "template1", "myapp", "otherdb"));
        when(postgresRepo.getDatabaseSizes()).thenReturn(Map.of("myapp", 1024L, "otherdb", 2048L));
        ManagedDatabase md = new ManagedDatabase("myapp", DatabaseEngineType.POSTGRES, "myapp_user", List.of("CONNECT:myapp"), NOW, NOW, null);
        when(managedRepo.findAllByEngineType(DatabaseEngineType.POSTGRES)).thenReturn(List.of(md));

        List<DatabaseInfo> dbs = service.listDatabases(DatabaseEngineType.POSTGRES);

        assertThat(dbs).extracting(DatabaseInfo::dbName).containsExactly("myapp", "otherdb");
        assertThat(dbs.stream().filter(d -> d.dbName().equals("myapp")).findFirst().orElseThrow().provisioned()).isTrue();
        assertThat(dbs.stream().filter(d -> d.dbName().equals("otherdb")).findFirst().orElseThrow().provisioned()).isFalse();
    }

    // ── getDatabase POSTGRES ────────────────────────────────────────

    @Test
    void getDatabasePostgresReturnsInfoWithConnectionString() {
        when(postgresRepo.databaseExists("myapp")).thenReturn(true);
        ManagedDatabase md = new ManagedDatabase("myapp", DatabaseEngineType.POSTGRES, "myapp_user", List.of("CONNECT:myapp"), NOW, NOW, null);
        md.setStoredPassword("mypass");
        when(managedRepo.findByEngineTypeAndDbName(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(Optional.of(md));
        when(postgresRepo.getDatabaseSizes()).thenReturn(Map.of("myapp", 4096L));

        DatabaseInfo info = service.getDatabase(DatabaseEngineType.POSTGRES, "myapp");

        assertThat(info.provisioned()).isTrue();
        assertThat(info.userName()).isEqualTo("myapp_user");
        assertThat(info.connectionString()).contains("myapp_user:mypass@");
        assertThat(info.sizeBytes()).isEqualTo(4096L);
    }

    @Test
    void getDatabasePostgresThrowsWhenMissing() {
        when(postgresRepo.databaseExists("missing")).thenReturn(false);
        assertThatThrownBy(() -> service.getDatabase(DatabaseEngineType.POSTGRES, "missing"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    // ── listUsers / revokeUser POSTGRES ─────────────────────────────

    @Test
    void listUsersPostgresReturnsUsers() {
        when(postgresRepo.databaseExists("myapp")).thenReturn(true);
        when(postgresRepo.getUsers("myapp")).thenReturn(List.of("myapp_user", "other_user"));

        var users = service.listUsers(DatabaseEngineType.POSTGRES, "myapp");

        assertThat(users).hasSize(2);
        assertThat(users.get(0).userName()).isEqualTo("myapp_user");
        assertThat(users.get(0).roles()).containsExactly("CONNECT:myapp");
    }

    @Test
    void revokeUserPostgresDropsRole() {
        when(postgresRepo.databaseExists("myapp")).thenReturn(true);

        service.revokeUser(DatabaseEngineType.POSTGRES, "myapp", "other_user");

        verify(postgresRepo).dropUser("myapp", "other_user");
    }

    @Test
    void revokeUserPostgresRejectsInvalidUserName() {
        assertThatThrownBy(() -> service.revokeUser(DatabaseEngineType.POSTGRES, "myapp", "Bad-User!"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    // ── postgres not enabled ────────────────────────────────────────

    @Test
    void provisionPostgresWhenNotEnabledThrows() {
        MongoDatabaseEngine mongoEngine = new MongoDatabaseEngine(mongoRepo, env);
        ProvisioningService noPg = new ProvisioningService(mongoRepo, managedRepo, auditRepo, new DatabaseNameValidator(),
                passwordGen, Clock.fixed(NOW, ZoneOffset.UTC), env, publisher,
                new DatabaseLockRegistry(), mongoEngine, null, null, null);

        assertThatThrownBy(() -> noPg.provision(new CreateDatabaseForm("myapp", DatabaseEngineType.POSTGRES, "myapp_user", "pass12345")))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Postgres is not enabled");
    }
}
