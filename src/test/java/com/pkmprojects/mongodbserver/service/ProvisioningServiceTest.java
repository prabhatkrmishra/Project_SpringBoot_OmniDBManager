package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.DatabaseUser;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import org.bson.Document;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.security.PasswordGenerator;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

/**
 * Unit tests for the provisioning lifecycle (mock repositories). Concurrency
 * behavior is covered separately by {@code ProvisioningConcurrencyTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Mock
    private MongoDatabaseRepository mongoDatabaseRepository;
    @Mock
    private ManagedDatabaseRepository managedDatabaseRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private PasswordGenerator passwordGenerator;
    @Mock
    private Environment environment;
    @Mock
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        // Only exercised by tests that build a connection string; lenient so the
        // lifecycle-only tests do not trip Mockito's strict stubbing.
        lenient().when(environment.getProperty("spring.mongodb.uri", ""))
                .thenReturn("mongodb://root:root@localhost:27017/?authSource=admin");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service = new ProvisioningService(mongoDatabaseRepository, managedDatabaseRepository,
                auditLogRepository, new MongoNameValidator(), passwordGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC), environment, applicationEventPublisher,
                new DatabaseLockRegistry());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void provisionCreatesUserDatabaseMetadataAndAudit() {
        when(passwordGenerator.generate(16)).thenReturn("generatedPass123");
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("_bootstrap"));

        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "appuser", ""));

        verify(mongoDatabaseRepository).createUser("myapp", "appuser", "generatedPass123");
        verify(mongoDatabaseRepository).createDatabase("myapp");
        ArgumentCaptor<ManagedDatabase> metadataCaptor = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(managedDatabaseRepository).save(metadataCaptor.capture());
        ManagedDatabase saved = metadataCaptor.getValue();
        assertThat(saved.getDbName()).isEqualTo("myapp");
        assertThat(saved.getUserName()).isEqualTo("appuser");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getLastPasswordResetAt()).isNull();
        assertThat(saved.getStoredPassword()).isEqualTo("generatedPass123");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.PROVISION);
        assertThat(auditCaptor.getValue().getPerformedBy()).isEqualTo("admin");
        assertThat(auditCaptor.getValue().getPerformedAt()).isEqualTo(NOW);

        ArgumentCaptor<AuditEventRecorded> eventCaptor = ArgumentCaptor.forClass(AuditEventRecorded.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().event().getEventType()).isEqualTo(AuditEvent.PROVISION);
        assertThat(eventCaptor.getValue().event().getDbName()).isEqualTo("myapp");

        assertThat(info.provisioned()).isTrue();
        assertThat(info.connectionString()).isEqualTo("mongodb://appuser:generatedPass123@localhost:27017/myapp?authSource=myapp");
    }

    @Test
    void provisionUsesExplicitPassword() {
        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "appuser", "mysecret123"));

        verify(mongoDatabaseRepository).createUser("myapp", "appuser", "mysecret123");
        ArgumentCaptor<ManagedDatabase> metadataCaptor = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(managedDatabaseRepository).save(metadataCaptor.capture());
        assertThat(metadataCaptor.getValue().getStoredPassword()).isEqualTo("mysecret123");
        assertThat(info.connectionString()).contains("appuser:mysecret123@");
    }

    @Test
    void buildConnectionStringPercentEncodesCredentials() {
        when(passwordGenerator.generate(16)).thenReturn("p@ss#word/x?y");

        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "app.user", ""));

        assertThat(info.connectionString())
                .isEqualTo("mongodb://app.user:p%40ss%23word%2Fx%3Fy@localhost:27017/myapp?authSource=myapp");
    }

    @Test
    void provisionEncodesSpecialCharactersInUserSuppliedPassword() {
        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "appuser", "s3cret%#@:"));

        assertThat(info.connectionString()).isEqualTo("mongodb://appuser:s3cret%25%23%40%3A@localhost:27017/myapp?authSource=myapp");
    }

    @Test
    void buildConnectionStringAddsTlsWhenConfigured() {
        // lenient: resolveConnectionHost's sibling getProperty calls are
        // intentionally unstubbed in this test.
        lenient().when(environment.getProperty("app.mongo-public-tls", Boolean.class, false)).thenReturn(true);
        when(passwordGenerator.generate(16)).thenReturn("generatedPass123");

        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "appuser", ""));

        assertThat(info.connectionString()).endsWith("?authSource=myapp&tls=true");
    }

    @Test
    void provisionRejectsExistingDatabase() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);

        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", "appuser", "")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
        verify(mongoDatabaseRepository, never()).createUser(any(), any(), any());
    }

    @Test
    void provisionRejectsExistingMetadata() {
        when(managedDatabaseRepository.existsByDbName("myapp")).thenReturn(true);

        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", "appuser", "")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
        verify(mongoDatabaseRepository, never()).createUser(any(), any(), any());
    }

    @Test
    void provisionFailureCleansUpPartialUser() {
        org.mockito.Mockito.doThrow(mongoError(13, "Unauthorized"))
                .when(mongoDatabaseRepository).createUser(eq("myapp"), eq("appuser"), any());

        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", "appuser", "mysecret123")))
                .isInstanceOf(ProvisioningException.class);

        verify(mongoDatabaseRepository).dropUser("myapp", "appuser");
        verify(managedDatabaseRepository, never()).save(any());
    }

    @Test
    void provisionMapsConcurrentDuplicateUserToConflict() {
        org.mockito.Mockito.doThrow(mongoError(51003, "UserAlreadyExists"))
                .when(mongoDatabaseRepository).createUser(eq("myapp"), eq("appuser"), any());

        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", "appuser", "mysecret123")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
    }

    @Test
    void resetPasswordRotatesAndReturnsNewCredentials() {
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));

        DatabaseInfo info = service.resetPassword("myapp", new ResetPasswordForm("newsecret456"));

        verify(mongoDatabaseRepository).updateUserPassword("myapp", "appuser", "newsecret456");
        assertThat(info.connectionString()).isEqualTo("mongodb://appuser:newsecret456@localhost:27017/myapp?authSource=myapp");
        assertThat(metadata.getLastPasswordResetAt()).isEqualTo(NOW);
        assertThat(metadata.getStoredPassword()).isEqualTo("newsecret456");
        verify(managedDatabaseRepository).save(metadata);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.RESET_PASSWORD);
    }

    @Test
    void resetPasswordGeneratesWhenBlank() {
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));
        when(passwordGenerator.generate(16)).thenReturn("rotatedPass456");

        service.resetPassword("myapp", new ResetPasswordForm(""));

        verify(mongoDatabaseRepository).updateUserPassword("myapp", "appuser", "rotatedPass456");
        assertThat(metadata.getStoredPassword()).isEqualTo("rotatedPass456");
    }

    @Test
    void resetPasswordOnUnprovisionedDatabaseThrows() {
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("myapp", new ResetPasswordForm("newsecret456")))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void deleteDropsUserDatabaseAndMetadata() {
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));

        service.delete("myapp");

        verify(mongoDatabaseRepository).dropDatabase("myapp");
        verify(mongoDatabaseRepository).dropUser("myapp", "appuser");
        verify(managedDatabaseRepository).deleteByDbName("myapp");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.DELETE);
    }

    @Test
    void deleteWithoutMetadataSkipsUserAndMetadata() {
        when(managedDatabaseRepository.findByDbName("externaldb")).thenReturn(Optional.empty());

        service.delete("externaldb");

        verify(mongoDatabaseRepository, never()).dropUser(any(), any());
        verify(mongoDatabaseRepository).dropDatabase("externaldb");
        verify(managedDatabaseRepository, never()).deleteByDbName(any());
    }

    @Test
    void deleteToleratesMissingUserAndNamespace() {
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));
        org.mockito.Mockito.doThrow(mongoError(26, "NamespaceNotFound"))
                .when(mongoDatabaseRepository).dropDatabase("myapp");
        org.mockito.Mockito.doThrow(mongoError(11, "UserNotFound"))
                .when(mongoDatabaseRepository).dropUser("myapp", "appuser");

        service.delete("myapp");

        verify(mongoDatabaseRepository).dropDatabase("myapp");
        verify(mongoDatabaseRepository).dropUser("myapp", "appuser");
        verify(managedDatabaseRepository).deleteByDbName("myapp");
    }

    @Test
    void deletePropagatesRealErrors() {
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(mongoError(13, "Unauthorized"))
                .when(mongoDatabaseRepository).dropDatabase("myapp");

        assertThatThrownBy(() -> service.delete("myapp"))
                .isInstanceOf(ProvisioningException.class);
        verify(managedDatabaseRepository, never()).deleteByDbName(any());
    }

    @Test
    void createCollectionRequiresExistingDatabase() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(false);

        assertThatThrownBy(() -> service.createCollection("myapp", "items"))
                .isInstanceOf(DatabaseNotFoundException.class);
        verify(mongoDatabaseRepository, never()).createCollection(any(), any());
    }

    @Test
    void createCollectionRejectsDuplicate() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);

        assertThatThrownBy(() -> service.createCollection("myapp", "items"))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
        verify(mongoDatabaseRepository, never()).createCollection(any(), any());
    }

    @Test
    void createCollectionSucceedsOnExistingDatabase() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(false);

        service.createCollection("myapp", "items");

        verify(mongoDatabaseRepository).createCollection("myapp", "items");
    }

    @Test
    void dropCollectionOnMissingCollectionThrows() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(false);

        assertThatThrownBy(() -> service.dropCollection("myapp", "items"))
                .isInstanceOf(DatabaseNotFoundException.class);
        verify(mongoDatabaseRepository, never()).dropCollection(any(), any());
    }

    @Test
    void dropCollectionSucceeds() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);

        service.dropCollection("myapp", "items");

        verify(mongoDatabaseRepository).dropCollection("myapp", "items");
    }

    @Test
    void listDatabasesExcludesSystemAndMetadataDatabases() {
        when(mongoDatabaseRepository.listDatabaseNames())
                .thenReturn(List.of("admin", "config", "local", "mongodb_admin", "myapp", "externaldb"));
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("_bootstrap"));
        when(mongoDatabaseRepository.listCollectionNames("externaldb")).thenReturn(List.of());
        when(mongoDatabaseRepository.getDatabaseSizes())
                .thenReturn(Map.of("myapp", 1024L, "externaldb", 2048L));
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findAll()).thenReturn(List.of(metadata));

        List<DatabaseInfo> databases = service.listDatabases();

        assertThat(databases).extracting(DatabaseInfo::dbName).containsExactly("externaldb", "myapp");
        DatabaseInfo myapp = databases.get(1);
        assertThat(myapp.provisioned()).isTrue();
        assertThat(myapp.userName()).isEqualTo("appuser");
        assertThat(myapp.collectionsCount()).isEqualTo(1);
        assertThat(myapp.sizeBytes()).isEqualTo(1024L);
        assertThat(databases.get(0).provisioned()).isFalse();
        assertThat(databases.get(0).sizeBytes()).isEqualTo(2048L);
    }

    @Test
    void getDatabaseThrowsWhenMissing() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(false);

        assertThatThrownBy(() -> service.getDatabase("myapp"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void listDatabasesPropagatesSizeOnDisk() {
        when(mongoDatabaseRepository.listDatabaseNames())
                .thenReturn(List.of("myapp"));
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("_bootstrap"));
        when(mongoDatabaseRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 524288L));
        when(managedDatabaseRepository.findAll()).thenReturn(List.of());

        List<DatabaseInfo> databases = service.listDatabases();

        assertThat(databases).hasSize(1);
        assertThat(databases.get(0).sizeBytes()).isEqualTo(524288L);
    }

    @Test
    void getDatabasePropagatesSizeOnDisk() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.empty());
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of());
        when(mongoDatabaseRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 1048576L));

        DatabaseInfo info = service.getDatabase("myapp");

        assertThat(info.sizeBytes()).isEqualTo(1048576L);
    }

    @Test
    void getDatabaseDegradesCollectionCountToUnknownWhenListingFails() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(mongoError(13, "Unauthorized"))
                .when(mongoDatabaseRepository).listCollectionNames("myapp");
        when(mongoDatabaseRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 1024L));

        DatabaseInfo info = service.getDatabase("myapp");

        // unknown must stay null (rendered as "—"), never collapse to 0
        assertThat(info.collectionsCount()).isNull();
    }

    @Test
    void getDatabaseReconstructsConnectionStringFromStoredPassword() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        metadata.setStoredPassword("mypassword");
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("items"));
        when(mongoDatabaseRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 4096L));

        DatabaseInfo info = service.getDatabase("myapp");

        assertThat(info.provisioned()).isTrue();
        assertThat(info.userName()).isEqualTo("appuser");
        assertThat(info.sizeBytes()).isEqualTo(4096L);
        assertThat(info.connectionString()).isEqualTo("mongodb://appuser:mypassword@localhost:27017/myapp?authSource=myapp");
    }

    @Test
    void getDatabaseReturnsNullConnectionStringWhenNoStoredPassword() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of());
        when(mongoDatabaseRepository.getDatabaseSizes()).thenReturn(Map.of());

        DatabaseInfo info = service.getDatabase("myapp");

        assertThat(info.provisioned()).isTrue();
        assertThat(info.sizeBytes()).isEqualTo(0L);
        assertThat(info.connectionString()).isNull();
    }

    @Test
    void resolveConnectionHostStripsCredentialsAndQuery() {
        when(environment.getProperty("app.mongo-public-host", "")).thenReturn("");
        when(environment.getProperty("spring.mongodb.uri", ""))
                .thenReturn("mongodb+srv://root:root@cluster0.abcd.mongodb.net/?retryWrites=true&w=majority");
        assertThat(service.resolveConnectionHost()).isEqualTo("cluster0.abcd.mongodb.net");

        when(environment.getProperty("spring.mongodb.uri", ""))
                .thenReturn("mongodb://root:root@localhost:27017/?authSource=admin");
        assertThat(service.resolveConnectionHost()).isEqualTo("localhost:27017");

        when(environment.getProperty("spring.mongodb.uri", "")).thenReturn("");
        assertThat(service.resolveConnectionHost()).isEqualTo("127.0.0.1:9812");

        when(environment.getProperty("app.mongo-public-host", ""))
                .thenReturn("mongo.pkmprojects.online:9812");
        assertThat(service.resolveConnectionHost()).isEqualTo("mongo.pkmprojects.online:9812");
    }

    @Test
    void provisionRejectsSystemDatabaseName() {
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("admin", "appuser", "")))
                .isInstanceOf(NameNotAllowedException.class);
        verify(mongoDatabaseRepository, never()).createUser(any(), any(), any());
    }

    @Test
    void listUsersReturnsUsersFromRepository() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        Document userDoc = new Document("user", "appuser")
                .append("roles", List.of(new Document("role", "readWrite").append("db", "myapp")))
                .append("db", "myapp");
        when(mongoDatabaseRepository.getUsers("myapp")).thenReturn(List.of(userDoc));

        List<DatabaseUser> users = service.listUsers("myapp");

        assertThat(users).hasSize(1);
        assertThat(users.get(0).userName()).isEqualTo("appuser");
        assertThat(users.get(0).roles()).containsExactly("readWrite:myapp");
        assertThat(users.get(0).authSource()).isEqualTo("myapp");
    }

    @Test
    void listUsersThrowsWhenDatabaseMissing() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(false);

        assertThatThrownBy(() -> service.listUsers("myapp"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void revokeUserDropsUserAndAudits() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        Document user1 = new Document("user", "appuser").append("roles", List.of()).append("db", "myapp");
        Document user2 = new Document("user", "otheruser").append("roles", List.of()).append("db", "myapp");
        when(mongoDatabaseRepository.getUsers("myapp")).thenReturn(List.of(user1, user2));

        service.revokeUser("myapp", "appuser");

        verify(mongoDatabaseRepository).dropUser("myapp", "appuser");
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.REVOKE_USER);
        assertThat(auditCaptor.getValue().getDbName()).isEqualTo("myapp");
        assertThat(auditCaptor.getValue().getUserName()).isEqualTo("appuser");
    }

    @Test
    void revokeUserRejectsInvalidUserName() {
        assertThatThrownBy(() -> service.revokeUser("myapp", "invalid user!"))
                .isInstanceOf(NameNotAllowedException.class);
        verify(mongoDatabaseRepository, never()).dropUser(any(), any());
    }

    @Test
    void revokeUserRejectsDroppingLastUser() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        Document user1 = new Document("user", "appuser").append("roles", List.of()).append("db", "myapp");
        when(mongoDatabaseRepository.getUsers("myapp")).thenReturn(List.of(user1));

        assertThatThrownBy(() -> service.revokeUser("myapp", "appuser"))
                .isInstanceOf(DatabaseAlreadyExistsException.class)
                .hasMessageContaining("last user");
        verify(mongoDatabaseRepository, never()).dropUser(any(), any());
    }

    @Test
    void revokeUserThrowsWhenDatabaseMissing() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(false);

        assertThatThrownBy(() -> service.revokeUser("myapp", "appuser"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    private MongoCommandException mongoError(int code, String message) {
        return new MongoCommandException(
                new BsonDocument("ok", new BsonInt32(0))
                        .append("code", new BsonInt32(code))
                        .append("errmsg", new BsonString(message)),
                new ServerAddress("localhost", 27017));
    }
}
