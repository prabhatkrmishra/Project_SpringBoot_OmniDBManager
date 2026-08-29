package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import com.pkmprojects.mongodbserver.store.AuditStore;
import org.bson.Document;
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
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresBackupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Mock
    private PostgresDatabaseRepository postgresRepository;
    @Mock
    private AuditStore auditStore;
    @Mock
    private ApplicationEventPublisher publisher;

    private PostgresBackupService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service = new PostgresBackupService(postgresRepository, new DatabaseNameValidator(),
                auditStore, publisher, new DatabaseLockRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private byte[] gzip(Document doc) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(doc.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    private byte[] backupBytes(String dbName, int version, List<Document> tables) {
        return gzip(new Document("formatVersion", version)
                .append("engine", "POSTGRES")
                .append("database", dbName)
                .append("backedUpAt", NOW.toString())
                .append("tables", tables));
    }

    private Document table(String name, List<String> columns, List<Document> rows) {
        return new Document("name", name).append("columns", columns).append("rows", rows);
    }

    private String decompress(byte[] bytes) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── writeBackup ─────────────────────────────────────────────────

    @Test
    void writeBackupStreamsTablesAndRows() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name", "email"));
        when(postgresRepository.listRows("myapp", "users", 1000, 0))
                .thenReturn(List.of(
                        Map.of("name", "alice", "email", "alice@example.com"),
                        Map.of("name", "bob", "email", "bob@example.com")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupService.BackupResult result = service.writeBackup("myapp", out);

        assertThat(result.collectionCount()).isEqualTo(1);
        assertThat(result.documentCount()).isEqualTo(2);

        Document root = Document.parse(decompress(out.toByteArray()));
        assertThat(root.getInteger("formatVersion")).isEqualTo(1);
        assertThat(root.getString("engine")).isEqualTo("POSTGRES");
        assertThat(root.getString("database")).isEqualTo("myapp");
        List<Document> tables = root.getList("tables", Document.class);
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).getString("name")).isEqualTo("users");
        assertThat(tables.get(0).getList("columns", String.class)).containsExactly("name", "email");
        List<Document> rows = tables.get(0).getList("rows", Document.class);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getString("name")).isEqualTo("alice");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditStore).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEvent.BACKUP_CREATED);
        verify(publisher).publishEvent(any(AuditEventRecorded.class));
    }

    @Test
    void writeBackupOfMissingDatabaseThrows() {
        when(postgresRepository.databaseExists("missing")).thenReturn(false);
        assertThatThrownBy(() -> service.writeBackup("missing", new ByteArrayOutputStream()))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void writeBackupRejectsInvalidDbName() {
        assertThatThrownBy(() -> service.writeBackup("MyApp", new ByteArrayOutputStream()))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void writeBackupWrapsListTablesFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenThrow(new RuntimeException("boom"));
        assertThatThrownBy(() -> service.writeBackup("myapp", new ByteArrayOutputStream()))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void writeBackupWrapsGetTableColumnsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.getTableColumns("myapp", "users")).thenThrow(new RuntimeException("col boom"));
        assertThatThrownBy(() -> service.writeBackup("myapp", new ByteArrayOutputStream()))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("columns");
    }

    @Test
    void writeBackupWrapsListRowsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRows("myapp", "users", 1000, 0)).thenThrow(new RuntimeException("row boom"));
        assertThatThrownBy(() -> service.writeBackup("myapp", new ByteArrayOutputStream()))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("rows");
    }

    @Test
    void writeBackupEmptyDatabaseProducesEmptyTablesArray() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupService.BackupResult result = service.writeBackup("myapp", out);

        assertThat(result.collectionCount()).isZero();
        assertThat(result.documentCount()).isZero();
        Document root = Document.parse(decompress(out.toByteArray()));
        assertThat(root.getList("tables", Document.class)).isEmpty();
    }

    @Test
    void writeBackupMultipleTablesWithPagination() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users", "orders"));
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.getTableColumns("myapp", "orders")).thenReturn(List.of("id"));
        // users: 1000 rows triggers second fetch, then 1 more
        List<Map<String, Object>> firstBatch = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) firstBatch.add(Map.of("name", "user-" + i));
        when(postgresRepository.listRows("myapp", "users", 1000, 0)).thenReturn(firstBatch);
        when(postgresRepository.listRows("myapp", "users", 1000, 1000)).thenReturn(List.of(Map.of("name", "last")));
        when(postgresRepository.listRows("myapp", "orders", 1000, 0))
                .thenReturn(List.of(Map.of("id", "1"), Map.of("id", "2")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupService.BackupResult result = service.writeBackup("myapp", out);

        assertThat(result.collectionCount()).isEqualTo(2);
        assertThat(result.documentCount()).isEqualTo(1003);
        Document root = Document.parse(decompress(out.toByteArray()));
        assertThat(root.getList("tables", Document.class)).hasSize(2);
    }

    @Test
    void writeBackupHandlesSpecialCharsInRows() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name", "note"));
        when(postgresRepository.listRows("myapp", "users", 1000, 0))
                .thenReturn(List.of(Map.of("name", "a\"b", "note", "line\nbreak")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeBackup("myapp", out);
        Document root = Document.parse(decompress(out.toByteArray()));
        List<Document> rows = root.getList("tables", Document.class).get(0).getList("rows", Document.class);
        assertThat(rows.get(0).getString("name")).isEqualTo("a\"b");
        assertThat(rows.get(0).getString("note")).isEqualTo("line\nbreak");
    }

    @Test
    void describeDatabaseHandlesListTablesFailureReturnsZero() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenThrow(new RuntimeException("boom"));
        BackupService.DatabaseBackupInfo info = service.describeDatabase("myapp");
        assertThat(info.exists()).isTrue();
        assertThat(info.collectionCount()).isZero();
    }

    @Test
    void describeDatabaseReportsExistenceAndTableCount() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users", "orders"));
        BackupService.DatabaseBackupInfo info = service.describeDatabase("myapp");
        assertThat(info.exists()).isTrue();
        assertThat(info.collectionCount()).isEqualTo(2);
    }

    @Test
    void describeDatabaseForMissingReturnsNotExists() {
        when(postgresRepository.databaseExists("missing")).thenReturn(false);
        BackupService.DatabaseBackupInfo info = service.describeDatabase("missing");
        assertThat(info.exists()).isFalse();
        assertThat(info.collectionCount()).isZero();
    }

    // ── restore ─────────────────────────────────────────────────────

    @Test
    void restoreCreatesMissingTableAndInsertsRows() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(false);
        byte[] content = backupBytes("myapp", 1, List.of(table("users",
                List.of("name", "email"),
                List.of(new Document("name", "alice").append("email", "alice@example.com")))));

        BackupService.RestoreResult result = service.restore("myapp", content, true);

        assertThat(result.collectionsRestored()).isEqualTo(1);
        assertThat(result.documentsRestored()).isEqualTo(1);
        verify(postgresRepository).executeInDatabase(eq("myapp"), contains("CREATE TABLE"));
        verify(postgresRepository).truncateTable("myapp", "users");
        verify(postgresRepository).insertRows(eq("myapp"), eq("users"), eq(List.of("name", "email")), anyList());
        verify(auditStore).save(any(AuditEvent.class));
    }

    @Test
    void restoreTruncatesExistingTableAndReconcilesColumns() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        byte[] content = backupBytes("myapp", 1, List.of(table("users",
                List.of("name", "email"),
                List.of(new Document("name", "alice").append("email", "alice@example.com")))));

        service.restore("myapp", content, true);

        verify(postgresRepository).executeInDatabase(eq("myapp"), contains("ADD COLUMN"));
        verify(postgresRepository).truncateTable("myapp", "users");
    }

    @Test
    void restoreBatchesLargeTables() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "items")).thenReturn(false);
        List<Document> many = new java.util.ArrayList<>();
        for (int i = 0; i < 2500; i++) many.add(new Document("name", "item-" + i));
        byte[] content = backupBytes("myapp", 1, List.of(table("items", List.of("name"), many)));

        service.restore("myapp", content, true);

        verify(postgresRepository, times(3)).insertRows(eq("myapp"), eq("items"), eq(List.of("name")), anyList());
    }

    @Test
    void restoreWithoutConfirmationRejected() {
        byte[] content = backupBytes("myapp", 1, List.of(table("users", List.of("name"), List.of())));
        assertThatThrownBy(() -> service.restore("myapp", content, false))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void restoreRejectsMalformedGzip() {
        assertThatThrownBy(() -> service.restore("myapp", "not gzip".getBytes(StandardCharsets.UTF_8), true))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void restoreRejectsUnsupportedVersion() {
        byte[] content = backupBytes("myapp", 99, List.of(table("users", List.of("name"), List.of())));
        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("format version");
    }

    @Test
    void restoreRejectsInvalidTableName() {
        byte[] content = backupBytes("myapp", 1, List.of(table("Bad-Name!", List.of("name"), List.of())));
        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void restoreRejectsDuplicateTableNames() {
        byte[] content = backupBytes("myapp", 1, List.of(
                table("users", List.of("name"), List.of()),
                table("users", List.of("name"), List.of())));
        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void restoreRejectsMongoBackup() {
        // Mongo backup has empty "tables" + non-empty "collections" -> detected as Mongo
        Document mongoBackup = new Document("formatVersion", 1)
                .append("database", "myapp")
                .append("tables", List.of())
                .append("collections", List.of(new Document("name", "users")));
        byte[] content = gzip(mongoBackup);
        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("MongoDB");
    }

    @Test
    void restoreRejectsMissingTablesSection() {
        byte[] content = gzip(new Document("formatVersion", 1).append("database", "myapp"));
        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void restoreThrowsWhenDatabaseNotProvisioned() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(false);
        byte[] content = backupBytes("myapp", 1, List.of(table("users", List.of("name"), List.of(new Document("name", "alice")))));
        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void restoreWrapsInsertFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(false);
        doThrow(new RuntimeException("boom")).when(postgresRepository).insertRows(anyString(), anyString(), anyList(), anyList());
        byte[] content = backupBytes("myapp", 1, List.of(table("users", List.of("name"), List.of(new Document("name", "alice")))));
        assertThatThrownBy(() -> service.restore("myapp", content, true))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void requireDatabaseExistsThrowsWhenMissing() {
        when(postgresRepository.databaseExists("missing")).thenReturn(false);
        assertThatThrownBy(() -> service.requireDatabaseExists("missing"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void requireDatabaseExistsRejectsInvalidName() {
        assertThatThrownBy(() -> service.requireDatabaseExists("MyApp"))
                .isInstanceOf(NameNotAllowedException.class);
    }
}
