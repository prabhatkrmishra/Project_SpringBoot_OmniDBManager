package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for backup creation and restore (mock repository).
 */
@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Mock
    private MongoDatabaseRepository mongoDatabaseRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private BackupService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service = new BackupService(mongoDatabaseRepository, new MongoNameValidator(),
                auditLogRepository, applicationEventPublisher, new DatabaseLockRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Document alice() {
        return new Document("_id", new ObjectId("507f1f77bcf86cd799439011"))
                .append("name", "alice")
                .append("createdAt", Date.from(Instant.parse("2026-08-18T10:00:00Z")));
    }

    private Document bob() {
        return new Document("_id", new ObjectId("507f1f77bcf86cd799439012"))
                .append("name", "bob");
    }

    private byte[] gzip(Document root) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(root.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    private byte[] backupBytes(String dbName, int formatVersion, List<Document> collections) {
        return gzip(new Document("formatVersion", formatVersion)
                .append("database", dbName)
                .append("backedUpAt", "2026-08-18T10:00:00Z")
                .append("collections", collections));
    }

    private Document collection(String name, List<Document> indexes, List<Document> documents) {
        return new Document("name", name).append("indexes", indexes).append("documents", documents);
    }

    private String decompress(byte[] bytes) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private MongoCommandException mongoError(int code, String message) {
        return new MongoCommandException(
                new BsonDocument("ok", new BsonInt32(0))
                        .append("code", new BsonInt32(code))
                        .append("errmsg", new BsonString(message)),
                new ServerAddress("localhost", 27017));
    }

    // ── Backup ──────────────────────────────────────────────────────────

    @Test
    void writeBackupStreamsCanonicalJsonWithDocumentsAndIndexes() throws Exception {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("users"));
        when(mongoDatabaseRepository.listCollectionIndexes("myapp", "users"))
                .thenReturn(List.of(new Document("v", 2)
                        .append("key", new Document("email", 1))
                        .append("name", "email_1")
                        .append("unique", true)));
        doAnswer(invocation -> {
            Consumer<Document> consumer = invocation.getArgument(2);
            consumer.accept(alice());
            consumer.accept(bob());
            return null;
        }).when(mongoDatabaseRepository).streamDocuments(eq("myapp"), eq("users"), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupService.BackupResult result = service.writeBackup("myapp", out);

        assertThat(result.collectionCount()).isEqualTo(1);
        assertThat(result.documentCount()).isEqualTo(2);

        Document root = Document.parse(decompress(out.toByteArray()));
        assertThat(root.getInteger("formatVersion")).isEqualTo(1);
        assertThat(root.getString("database")).isEqualTo("myapp");
        assertThat(root.getString("backedUpAt")).isEqualTo(NOW.toString());

        List<Document> collections = root.getList("collections", Document.class);
        assertThat(collections).hasSize(1);
        assertThat(collections.get(0).getString("name")).isEqualTo("users");
        assertThat(collections.get(0).getList("indexes", Document.class))
                .anyMatch(index -> "email_1".equals(index.getString("name"))
                        && Boolean.TRUE.equals(index.getBoolean("unique")));

        List<Document> documents = collections.get(0).getList("documents", Document.class);
        assertThat(documents).hasSize(2);
        // canonical extended JSON round-trips types exactly
        assertThat(documents.get(0).getObjectId("_id")).isEqualTo(new ObjectId("507f1f77bcf86cd799439011"));
        assertThat(documents.get(0).getDate("createdAt")).isEqualTo(Date.from(Instant.parse("2026-08-18T10:00:00Z")));
        assertThat(documents.get(1).getString("name")).isEqualTo("bob");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.BACKUP_CREATED);
        assertThat(auditCaptor.getValue().getDbName()).isEqualTo("myapp");
        assertThat(auditCaptor.getValue().getPerformedBy()).isEqualTo("admin");
        verify(applicationEventPublisher).publishEvent(any(AuditEventRecorded.class));
    }

    @Test
    void writeBackupOfMissingDatabaseThrowsNotFound() {
        when(mongoDatabaseRepository.databaseExists("missing")).thenReturn(false);

        assertThatThrownBy(() -> service.writeBackup("missing", new ByteArrayOutputStream()))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void writeBackupWrapsDriverFailureAsProvisioningException() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.listCollectionNames("myapp"))
                .thenThrow(mongoError(1, "boom"));

        assertThatThrownBy(() -> service.writeBackup("myapp", new ByteArrayOutputStream()))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void describeDatabaseReportsExistenceAndCollectionCount() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("users", "orders"));

        BackupService.DatabaseBackupInfo info = service.describeDatabase("myapp");

        assertThat(info.dbName()).isEqualTo("myapp");
        assertThat(info.exists()).isTrue();
        assertThat(info.collectionCount()).isEqualTo(2);
    }

    // ── Restore ─────────────────────────────────────────────────────────

    @Test
    void restoreReplacesExistingCollectionsAndRecreatesIndexes() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("old_collection"));
        byte[] content = backupBytes("myapp", 1, List.of(collection("users",
                List.of(new Document("key", new Document("_id", 1)).append("name", "_id_"),
                        new Document("key", new Document("email", 1)).append("name", "email_1").append("unique", true)),
                List.of(alice(), bob()))));

        BackupService.RestoreResult result = service.restore("myapp", content, true);

        assertThat(result.collectionsRestored()).isEqualTo(1);
        assertThat(result.documentsRestored()).isEqualTo(2);
        verify(mongoDatabaseRepository).dropCollection("myapp", "old_collection");
        verify(mongoDatabaseRepository).createCollection("myapp", "users");
        // the implicit _id_ index is skipped, the unique index is recreated
        verify(mongoDatabaseRepository).createIndex(eq("myapp"), eq("users"), eq(new Document("email", 1)), eq(true));
        verify(mongoDatabaseRepository, never()).createIndex(eq("myapp"), eq("users"), eq(new Document("_id", 1)), anyBoolean());
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), anyList());

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.BACKUP_RESTORED);
        verify(applicationEventPublisher).publishEvent(any(AuditEventRecorded.class));
    }

    @Test
    void restoreIntoFreshDatabaseDoesNotDropAnything() {
        when(mongoDatabaseRepository.databaseExists("fresh")).thenReturn(false);
        byte[] content = backupBytes("fresh", 1, List.of(collection("users", List.of(), List.of(alice()))));

        BackupService.RestoreResult result = service.restore("fresh", content, true);

        assertThat(result.documentsRestored()).isEqualTo(1);
        verify(mongoDatabaseRepository, never()).dropCollection(any(), any());
        verify(mongoDatabaseRepository).createCollection("fresh", "users");
        verify(mongoDatabaseRepository).insertDocuments(eq("fresh"), eq("users"), anyList());
    }

    @Test
    void restoreBatchesLargeCollections() {
        when(mongoDatabaseRepository.databaseExists("big")).thenReturn(false);
        List<Document> many = new java.util.ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            many.add(new Document("_id", i).append("n", i));
        }
        byte[] content = backupBytes("big", 1, List.of(collection("items", List.of(), many)));

        service.restore("big", content, true);

        verify(mongoDatabaseRepository, times(3)).insertDocuments(eq("big"), eq("items"), anyList());
    }

    @Test
    void restoreWithoutConfirmationIsRejected() {
        byte[] content = backupBytes("myapp", 1, List.of(collection("users", List.of(), List.of())));

        assertThatThrownBy(() -> service.restore("myapp", content, false))
                .isInstanceOf(NameNotAllowedException.class);
        verify(mongoDatabaseRepository, never()).dropCollection(any(), any());
        verify(mongoDatabaseRepository, never()).createCollection(any(), any());
    }

    @Test
    void restoreRejectsMalformedGzip() {
        assertThatThrownBy(() -> service.restore("myapp", "not gzip".getBytes(StandardCharsets.UTF_8), true))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void restoreRejectsUnsupportedFormatVersion() {
        byte[] content = backupBytes("myapp", 99, List.of(collection("users", List.of(), List.of())));

        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("format version");
    }

    @Test
    void restoreRejectsInvalidCollectionName() {
        byte[] content = backupBytes("myapp", 1, List.of(collection("bad name!", List.of(), List.of())));

        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void restoreRejectsDuplicateCollectionNames() {
        byte[] content = backupBytes("myapp", 1, List.of(
                collection("users", List.of(), List.of()),
                collection("users", List.of(), List.of())));

        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void restoreRejectsMalformedIndexEntry() {
        byte[] content = backupBytes("myapp", 1, List.of(collection("users",
                List.of(new Document("key", "email").append("name", "email_1")), List.of())));

        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void restoreRejectsMissingCollectionsSection() {
        byte[] content = gzip(new Document("formatVersion", 1).append("database", "myapp"));

        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void restoreWrapsDriverFailureAsProvisioningException() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(false);
        doThrow(mongoError(1, "boom")).when(mongoDatabaseRepository).createCollection("myapp", "users");
        byte[] content = backupBytes("myapp", 1, List.of(collection("users", List.of(), List.of(alice()))));

        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(ProvisioningException.class);
    }
}