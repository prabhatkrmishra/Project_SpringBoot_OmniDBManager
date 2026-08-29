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
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end backup/restore round trip against a real MongoDB: write data and
 * an index, back the database up, drop it, restore it, and verify the data and
 * index came back exactly.
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
class BackupRestoreTest {

    @Container
    static GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root")
            .withExposedPorts(27017)
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2));

    @Autowired
    private MongoDatabaseRepository mongoDatabaseRepository;

    @Autowired
    private BackupService backupService;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> "mongodb://root:root@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?authSource=admin");
    }

    @AfterEach
    void cleanup() {
        mongoDatabaseRepository.dropDatabase("backup_roundtrip");
    }

    @Test
    void backupRestoreRoundTripPreservesDocumentsAndIndexes() throws Exception {
        String dbName = "backup_roundtrip";
        mongoDatabaseRepository.createCollection(dbName, "users");
        mongoDatabaseRepository.createIndex(dbName, "users", new Document("email", 1), true);
        mongoDatabaseRepository.insertDocuments(dbName, "users", List.of(
                new Document("_id", new ObjectId("507f1f77bcf86cd799439011"))
                        .append("name", "alice")
                        .append("email", "alice@example.com")
                        .append("createdAt", Date.from(Instant.parse("2026-08-18T10:00:00Z"))),
                new Document("_id", new ObjectId("507f1f77bcf86cd799439012"))
                        .append("name", "bob")
                        .append("email", "bob@example.com")));

        ByteArrayOutputStream backupBytes = new ByteArrayOutputStream();
        BackupService.BackupResult backup = backupService.writeBackup(dbName, backupBytes);
        assertThat(backup.documentCount()).isEqualTo(2);

        mongoDatabaseRepository.dropDatabase(dbName);
        assertThat(mongoDatabaseRepository.databaseExists(dbName)).isFalse();

        BackupService.RestoreResult restored = backupService.restore(dbName, backupBytes.toByteArray(), true);
        assertThat(restored.collectionsRestored()).isEqualTo(1);
        assertThat(restored.documentsRestored()).isEqualTo(2);

        List<Document> reloaded = mongoDatabaseRepository.findDocuments(dbName, "users", 0, 100);
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(0).getObjectId("_id")).isEqualTo(new ObjectId("507f1f77bcf86cd799439011"));
        assertThat(reloaded.get(0).getDate("createdAt")).isEqualTo(Date.from(Instant.parse("2026-08-18T10:00:00Z")));

        List<Document> indexes = mongoDatabaseRepository.listCollectionIndexes(dbName, "users");
        assertThat(indexes).anyMatch(index -> "email_1".equals(index.getString("name"))
                && Boolean.TRUE.equals(index.getBoolean("unique")));
    }
}