package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for bulk collection export and import (mock repository).
 */
@ExtendWith(MockitoExtension.class)
class ImportExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Mock
    private MongoDatabaseRepository mongoDatabaseRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private ImportExportService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service = new ImportExportService(mongoDatabaseRepository, new MongoNameValidator(),
                auditLogRepository, applicationEventPublisher, new DatabaseLockRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void stubExistingCollection() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "users")).thenReturn(true);
    }

    private Document alice() {
        return new Document("_id", new ObjectId("507f1f77bcf86cd799439011"))
                .append("name", "alice")
                .append("createdAt", Date.from(Instant.parse("2026-08-18T10:00:00Z")))
                .append("profile", new Document("city", "X"));
    }

    private Document bob() {
        return new Document("_id", new ObjectId("507f1f77bcf86cd799439012"))
                .append("name", "bob");
    }

    private void feed(List<Document> documents) {
        doAnswer(invocation -> {
            Consumer<Document> consumer = invocation.getArgument(2);
            documents.forEach(consumer);
            return null;
        }).when(mongoDatabaseRepository).streamDocuments(eq("myapp"), eq("users"), any());
    }

    private MongoCommandException mongoError(int code, String message) {
        return new MongoCommandException(
                new BsonDocument("ok", new BsonInt32(0))
                        .append("code", new BsonInt32(code))
                        .append("errmsg", new BsonString(message)),
                new ServerAddress("localhost", 27017));
    }

    // ── JSON export ─────────────────────────────────────────────────────

    @Test
    void jsonExportStreamsArrayAndPreservesTypes() throws Exception {
        stubExistingCollection();
        feed(List.of(alice(), bob()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeAllDocumentsAsJson("myapp", "users", out);

        Document root = Document.parse("{\"documents\":" + out.toString(StandardCharsets.UTF_8) + "}");
        List<Document> documents = root.getList("documents", Document.class);
        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).getObjectId("_id")).isEqualTo(new ObjectId("507f1f77bcf86cd799439011"));
        assertThat(documents.get(0).getDate("createdAt")).isEqualTo(Date.from(Instant.parse("2026-08-18T10:00:00Z")));
        assertThat(documents.get(0).get("profile", Document.class).getString("city")).isEqualTo("X");
        assertThat(documents.get(1).getString("name")).isEqualTo("bob");
    }

    @Test
    void jsonExportOfMissingCollectionThrowsNotFound() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "users")).thenReturn(false);

        assertThatThrownBy(() -> service.writeAllDocumentsAsJson("myapp", "users", new ByteArrayOutputStream()))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void jsonExportWrapsDriverFailureAsProvisioningException() {
        stubExistingCollection();
        doThrow(mongoError(1, "boom"))
                .when(mongoDatabaseRepository).streamDocuments(eq("myapp"), eq("users"), any());

        assertThatThrownBy(() -> service.writeAllDocumentsAsJson("myapp", "users", new ByteArrayOutputStream()))
                .isInstanceOf(ProvisioningException.class);
    }

    // ── CSV export ──────────────────────────────────────────────────────

    @Test
    void csvExportWritesHeaderRowsAndEscapesCells() throws Exception {
        stubExistingCollection();
        feed(List.of(
                new Document("name", "Alice").append("city", "Springfield"),
                new Document("name", "Bob, \"Jr\"").append("city", "Line\nBreak"),
                new Document("name", "Carol").append("age", 30),
                new Document("name", "Dave").append("when", Date.from(Instant.parse("2026-08-18T10:00:00Z")))));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeAllDocumentsAsCsv("myapp", "users", out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF"); // UTF-8 BOM for spreadsheet compatibility
        assertThat(csv).contains("\r\n");
        assertThat(csv).contains("name,city,age,when");
        assertThat(csv).contains("Alice,Springfield,");
        assertThat(csv).contains("\"Bob, \"\"Jr\"\"\"");
        assertThat(csv).contains("\"Line\nBreak\"");
        assertThat(csv).contains(",30,");
        assertThat(csv).contains("2026-08-18T10:00:00Z");
    }

    @Test
    void csvExportRendersNestedValuesAsCompactJson() throws Exception {
        stubExistingCollection();
        feed(List.of(new Document("name", "alice")
                .append("profile", new Document("city", "X"))
                .append("tags", List.of("a", "b"))));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeAllDocumentsAsCsv("myapp", "users", out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"{\"\"city\"\": \"\"X\"\"}\"");
        assertThat(csv).contains("\"[a,b]\"");
    }

    // ── JSON import ─────────────────────────────────────────────────────

    @Test
    void jsonImportArrayInsertsAllDocuments() {
        stubExistingCollection();
        String json = "[{\"name\":\"alice\"},{\"name\":\"bob\"}]";

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                json.getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(2);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getString("name")).isEqualTo("alice");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.IMPORT);
        assertThat(auditCaptor.getValue().getDbName()).isEqualTo("myapp");
        assertThat(auditCaptor.getValue().getUserName()).isEqualTo("users");
        assertThat(auditCaptor.getValue().getPerformedBy()).isEqualTo("admin");
        verify(applicationEventPublisher).publishEvent(any(AuditEventRecorded.class));
    }

    @Test
    void jsonImportSingleObjectInsertsOneDocument() {
        stubExistingCollection();

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                "{\"name\":\"solo\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(1);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), anyList());
    }

    @Test
    void jsonImportEmptyArrayInsertsNothing() {
        stubExistingCollection();

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                "[]".getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(0);
        verify(mongoDatabaseRepository, never()).insertDocuments(any(), any(), anyList());
    }

    @Test
    void jsonImportBatchesLargeArrays() {
        stubExistingCollection();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 2500; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"n\":").append(i).append('}');
        }
        json.append(']');

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                json.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(2500);
        verify(mongoDatabaseRepository, times(3)).insertDocuments(eq("myapp"), eq("users"), anyList());
    }

    @Test
    void jsonImportPreservesExtendedJsonTypes() {
        stubExistingCollection();

        service.importDocuments("myapp", "users",
                ("[{\"_id\":{\"$oid\":\"507f1f77bcf86cd799439011\"},"
                        + "\"when\":{\"$date\":\"2026-08-18T10:00:00Z\"}}]").getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue().get(0).getObjectId("_id")).isEqualTo(new ObjectId("507f1f77bcf86cd799439011"));
        assertThat(captor.getValue().get(0).getDate("when")).isEqualTo(Date.from(Instant.parse("2026-08-18T10:00:00Z")));
    }

    @Test
    void jsonImportRejectsNonObjectElements() {
        // Parse failures reject before any repository access.

        assertThatThrownBy(() -> service.importDocuments("myapp", "users",
                "[1,2]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NameNotAllowedException.class);
        verify(mongoDatabaseRepository, never()).insertDocuments(any(), any(), anyList());
    }

    @Test
    void jsonImportRejectsMalformedJson() {
        assertThatThrownBy(() -> service.importDocuments("myapp", "users",
                "[{\"name\":".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NameNotAllowedException.class);
    }

    // ── CSV import ──────────────────────────────────────────────────────

    @Test
    void csvImportParsesHeaderAndRowsWithEmptyCellAsNull() {
        stubExistingCollection();

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                "name,age,notes\nAlice,30,hi\nBob,,\n".getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(2);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue().get(0).getString("name")).isEqualTo("Alice");
        assertThat(captor.getValue().get(0).getString("age")).isEqualTo("30");
        assertThat(captor.getValue().get(1).getString("name")).isEqualTo("Bob");
        assertThat(captor.getValue().get(1).get("age")).isNull();
        assertThat(captor.getValue().get(1).get("notes")).isNull();
    }

    @Test
    void csvImportHandlesQuotedFields() {
        stubExistingCollection();

        service.importDocuments("myapp", "users",
                ("name,note\n\"Smith, \"\"Jr\"\"\",\"line1\nline2\"\n").getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue().get(0).getString("name")).isEqualTo("Smith, \"Jr\"");
        assertThat(captor.getValue().get(0).getString("note")).isEqualTo("line1\nline2");
    }

    @Test
    void csvImportRequiresHeaderRow() {
        assertThatThrownBy(() -> service.importDocuments("myapp", "users",
                "".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void csvImportRejectsDuplicateColumns() {
        assertThatThrownBy(() -> service.importDocuments("myapp", "users",
                "a,a\n1,2\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void csvImportRejectsDollarColumn() {
        assertThatThrownBy(() -> service.importDocuments("myapp", "users",
                "$x\n1\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("'$'");
    }

    @Test
    void csvImportSkipsBlankLines() {
        stubExistingCollection();

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                "name,age\nAlice,30\n\nBob,31\n\n".getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(2);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getString("name")).isEqualTo("Alice");
        assertThat(captor.getValue().get(1).getString("name")).isEqualTo("Bob");
    }

    @Test
    void csvImportStripsUtf8BomFromHeader() {
        stubExistingCollection();

        service.importDocuments("myapp", "users",
                "\uFEFFname,age\nAlice,30\n".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue().get(0).get("name")).isEqualTo("Alice");
        assertThat(captor.getValue().get(0).get("\uFEFFname")).isNull();
    }

    @Test
    void jsonImportStripsUtf8Bom() {
        stubExistingCollection();

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                "\uFEFF[{\"name\":\"alice\"}]".getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(1);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue().get(0).getString("name")).isEqualTo("alice");
    }

    @Test
    void csvExportNeutralizesFormulaInjection() throws Exception {
        stubExistingCollection();
        feed(List.of(new Document("name", "=SUM(A1:A2)")
                .append("qty", "+1")
                .append("mail", "@cmd")
                .append("tab", "\t=x")
                .append("neg", -5)
                .append("expr", "-2+3")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeAllDocumentsAsCsv("myapp", "users", out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).contains("'=SUM(A1:A2)");
        assertThat(csv).contains("'+1");
        assertThat(csv).contains("'@cmd");
        assertThat(csv).contains("'\t=x");
        assertThat(csv).contains(",-5,");
        assertThat(csv).contains("'-2+3");
    }

    @Test
    void csvImportHandlesLoneCarriageReturns() {
        stubExistingCollection();

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                "a,b\r1,2\r3,4".getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(2);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue().get(0).getString("a")).isEqualTo("1");
        assertThat(captor.getValue().get(0).getString("b")).isEqualTo("2");
        assertThat(captor.getValue().get(1).getString("a")).isEqualTo("3");
        assertThat(captor.getValue().get(1).getString("b")).isEqualTo("4");
    }

    @Test
    void csvImportHandlesCrlfLineEndings() {
        stubExistingCollection();

        ImportExportService.ImportResult result = service.importDocuments("myapp", "users",
                "a,b\r\n1,2\r\n3,4\r\n".getBytes(StandardCharsets.UTF_8));

        assertThat(result.documentsImported()).isEqualTo(2);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getString("a")).isEqualTo("1");
        assertThat(captor.getValue().get(1).getString("a")).isEqualTo("3");
    }

    @Test
    void csvImportRejectsDottedColumn() {
        assertThatThrownBy(() -> service.importDocuments("myapp", "users",
                "a.b\n1\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("'.'");
    }

    // ── Import guards ───────────────────────────────────────────────────

    @Test
    void importEmptyFileIsRejected() {
        assertThatThrownBy(() -> service.importDocuments("myapp", "users", new byte[0]))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void importOfMissingCollectionThrowsNotFound() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "users")).thenReturn(false);

        assertThatThrownBy(() -> service.importDocuments("myapp", "users",
                "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void importWrapsDriverFailureAsProvisioningException() {
        stubExistingCollection();
        doThrow(mongoError(1, "boom"))
                .when(mongoDatabaseRepository).insertDocuments(eq("myapp"), eq("users"), anyList());

        assertThatThrownBy(() -> service.importDocuments("myapp", "users",
                "{\"name\":\"alice\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ProvisioningException.class);
    }
}