package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end bulk export/import round trip against a real MongoDB: export a
 * collection with mixed types, drop it, recreate it, import the JSON back, and
 * verify the data came back exactly; CSV imports append string rows.
 *
 * <p>Skipped when Docker is unavailable (same convention as the other
 * container-based tests).</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "app.mongo.enabled=true",
        "app.admin.username=admin",
        "app.admin.password=admin"
})
class ImportExportRoundTripTest {

    @Container
    static GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root")
            .withExposedPorts(27017)
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2));

    @Autowired
    private MongoDatabaseRepository mongoDatabaseRepository;

    @Autowired
    private ImportExportService importExportService;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> "mongodb://root:root@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?authSource=admin");
    }

    @AfterEach
    void cleanup() {
        mongoDatabaseRepository.dropDatabase("import_roundtrip");
    }

    @Test
    void jsonExportImportRoundTripPreservesTypes() throws Exception {
        String dbName = "import_roundtrip";
        mongoDatabaseRepository.createCollection(dbName, "users");
        mongoDatabaseRepository.insertDocuments(dbName, "users", List.of(
                new Document("_id", new ObjectId("507f1f77bcf86cd799439011"))
                        .append("name", "alice")
                        .append("createdAt", Date.from(Instant.parse("2026-08-18T10:00:00Z")))
                        .append("profile", new Document("city", "X"))
                        .append("tags", List.of("a", "b")),
                new Document("_id", new ObjectId("507f1f77bcf86cd799439012"))
                        .append("name", "bob")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        importExportService.writeAllDocumentsAsJson(dbName, "users", out);
        String exported = out.toString(StandardCharsets.UTF_8);
        Document root = Document.parse("{\"documents\":" + exported + "}");
        assertThat(root.getList("documents", Document.class)).hasSize(2);

        mongoDatabaseRepository.dropDatabase(dbName);
        mongoDatabaseRepository.createCollection(dbName, "users");

        ImportExportService.ImportResult result = importExportService.importDocuments(dbName, "users", out.toByteArray());
        assertThat(result.documentsImported()).isEqualTo(2);

        List<Document> reloaded = mongoDatabaseRepository.findDocuments(dbName, "users", 0, 100);
        assertThat(reloaded).hasSize(2);
        Document alice = reloaded.stream()
                .filter(doc -> doc.getObjectId("_id").equals(new ObjectId("507f1f77bcf86cd799439011")))
                .findFirst()
                .orElseThrow();
        assertThat(alice.getString("name")).isEqualTo("alice");
        assertThat(alice.getDate("createdAt")).isEqualTo(Date.from(Instant.parse("2026-08-18T10:00:00Z")));
        assertThat(alice.get("profile", Document.class).getString("city")).isEqualTo("X");
        assertThat(alice.getList("tags", String.class)).containsExactly("a", "b");
    }

    @Test
    void csvImportAppendsStringRowsAndNulls() {
        String dbName = "import_roundtrip";
        mongoDatabaseRepository.createCollection(dbName, "users");

        ImportExportService.ImportResult result = importExportService.importDocuments(dbName, "users",
                "name,age,notes\nAlice,30,hi\nBob,,\n".getBytes(StandardCharsets.UTF_8));
        assertThat(result.documentsImported()).isEqualTo(2);

        List<Document> reloaded = mongoDatabaseRepository.findDocuments(dbName, "users", 0, 100);
        assertThat(reloaded).hasSize(2);
        Document alice = reloaded.stream()
                .filter(doc -> "Alice".equals(doc.getString("name")))
                .findFirst()
                .orElseThrow();
        assertThat(alice.getString("age")).isEqualTo("30");
        assertThat(alice.getString("notes")).isEqualTo("hi");
        Document bob = reloaded.stream()
                .filter(doc -> "Bob".equals(doc.getString("name")))
                .findFirst()
                .orElseThrow();
        assertThat(bob.get("age")).isNull();
        assertThat(bob.get("notes")).isNull();
    }
}