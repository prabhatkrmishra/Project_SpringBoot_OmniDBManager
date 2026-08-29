package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.store.AuditLogRepositoryAdapter;
import com.pkmprojects.mongodbserver.store.AuditStore;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.util.Json;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Creates and restores per-database backups. Only loaded when {@code app.mongo.enabled=true}.
 *
 * <p>Backup format: a gzip'd JSON document
 * {@code {formatVersion: 1, database, backedUpAt, collections: [{name, indexes, documents}]}}.
 * Documents are serialized with canonical extended JSON so dates, ObjectIds and
 * binary data survive a round trip exactly. Writing streams one document at a
 * time (memory stays bounded regardless of collection size); restoring loads the
 * uploaded file fully and replaces the database's current content, recreating
 * collections, indexes and documents.</p>
 *
 * <p>Restore is intentionally replace-semantics: existing collections are
 * dropped first. The controller only calls {@link #restore} after the admin
 * confirmed this on the restore form, and the service re-checks the flag.</p>
 */
@Service
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    static final int FORMAT_VERSION = 1;
    static final int INSERT_BATCH_SIZE = 1000;

    private static final JsonWriterSettings CANONICAL = JsonWriterSettings.builder()
            .outputMode(JsonMode.EXTENDED)
            .build();

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final MongoNameValidator nameValidator;
    private final AuditStore auditStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DatabaseLockRegistry databaseLocks;
    private final Clock clock;

    public BackupService(MongoDatabaseRepository mongoDatabaseRepository,
                         MongoNameValidator nameValidator,
                         AuditStore auditStore,
                         ApplicationEventPublisher applicationEventPublisher,
                         DatabaseLockRegistry databaseLocks,
                         Clock clock) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.nameValidator = nameValidator;
        this.auditStore = auditStore;
        this.applicationEventPublisher = applicationEventPublisher;
        this.databaseLocks = databaseLocks;
        this.clock = clock;
    }

    /**
     * Legacy constructor used by unit tests that pass an
     * {@link AuditLogRepository} directly. Delegates to the store-based
     * constructor via {@link AuditLogRepositoryAdapter}.
     */
    public BackupService(MongoDatabaseRepository mongoDatabaseRepository,
                         MongoNameValidator nameValidator,
                         AuditLogRepository auditLogRepository,
                         ApplicationEventPublisher applicationEventPublisher,
                         DatabaseLockRegistry databaseLocks,
                         Clock clock) {
        this(mongoDatabaseRepository, nameValidator, new AuditLogRepositoryAdapter(auditLogRepository),
                applicationEventPublisher, databaseLocks, clock);
    }

    /**
     * Verifies that {@code dbName} is valid and exists on the server. The
     * download controller calls this <em>before</em> returning a streaming
     * response so a missing database produces a normal 404 page instead of a
     * truncated download.
     *
     * @throws DatabaseNotFoundException when the database does not exist
     * @throws NameNotAllowedException   when the name is invalid
     */
    public void requireDatabaseExists(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
    }

    /**
     * Information shown on the restore form: the database itself may not exist
     * yet (a restore creates it), so this cannot throw for a missing database.
     */
    public DatabaseBackupInfo describeDatabase(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        boolean exists = mongoDatabaseRepository.databaseExists(dbName);
        int collectionCount = exists ? mongoDatabaseRepository.listCollectionNames(dbName).size() : 0;
        return new DatabaseBackupInfo(dbName, exists, collectionCount);
    }

    /**
     * Streams a gzip'd JSON backup of the whole database to {@code out}.
     * Collection metadata and every document are written incrementally, so memory
     * use stays bounded regardless of data size. If writing fails mid-stream the
     * output is truncated - the caller receives the exception and the client sees
     * a broken download (a 500 cannot be sent after the response started).
     *
     * @return summary of what was backed up
     * @throws DatabaseNotFoundException when the database does not exist
     */
    public BackupResult writeBackup(String dbName, OutputStream out) {
        nameValidator.validateDatabaseName(dbName);
        requireDatabaseExists(dbName);
        List<String> collectionNames;
        try {
            collectionNames = mongoDatabaseRepository.listCollectionNames(dbName);
        } catch (MongoException e) {
            log.error("Failed to list collections for backup of database '{}'", dbName, e);
            throw new ProvisioningException("Could not back up database '" + dbName + "'", e);
        }

        long totalDocuments = 0;
        try (OutputStream gzip = new GZIPOutputStream(out)) {
            byte[] header = ("{\"formatVersion\":" + FORMAT_VERSION + ",\"database\":"
                    + Json.jsonString(dbName) + ",\"backedUpAt\":"
                    + Json.jsonString(clock.instant().toString()) + ",\"collections\":[")
                    .getBytes(StandardCharsets.UTF_8);
            gzip.write(header);
            boolean firstCollection = true;
            for (String collectionName : collectionNames) {
                if (!firstCollection) {
                    gzip.write(COMMA);
                }
                firstCollection = false;
                String prefix = "{\"name\":" + Json.jsonString(collectionName)
                        + ",\"indexes\":" + indexesToJson(dbName, collectionName) + ",\"documents\":[";
                gzip.write(prefix.getBytes(StandardCharsets.UTF_8));
                long[] collectionDocuments = {0};
                boolean[] firstDocument = {true};
                mongoDatabaseRepository.streamDocuments(dbName, collectionName, doc -> {
                    try {
                        if (!firstDocument[0]) {
                            gzip.write(COMMA);
                        }
                        firstDocument[0] = false;
                        gzip.write(doc.toJson(CANONICAL).getBytes(StandardCharsets.UTF_8));
                        collectionDocuments[0]++;
                    } catch (IOException e) {
                        throw new BackupWriteException(e);
                    }
                });
                gzip.write(COLLECTION_SUFFIX);
                totalDocuments += collectionDocuments[0];
            }
            gzip.write(DOCUMENT_ROOT_SUFFIX);
        } catch (BackupWriteException e) {
            log.error("Failed to write backup of database '{}'", dbName, e);
            throw new ProvisioningException("Could not back up database '" + dbName + "'", e);
        } catch (MongoException e) {
            log.error("Failed to back up database '{}'", dbName, e);
            throw new ProvisioningException("Could not back up database '" + dbName + "'", e);
        } catch (IOException e) {
            log.error("Failed to write backup of database '{}'", dbName, e);
            throw new ProvisioningException("Could not back up database '" + dbName + "'", e);
        }

        audit(AuditEvent.BACKUP_CREATED, dbName, clock.instant());
        log.info("Backed up database '{}': {} collections, {} documents",
                dbName, collectionNames.size(), totalDocuments);
        return new BackupResult(dbName, collectionNames.size(), totalDocuments);
    }

    /**
     * Restores a database from an uploaded backup file, replacing its current
     * content. Existing collections are dropped, then collections, indexes and
     * documents are recreated from the backup. A missing database is created.
     * All structural validation happens <em>before</em> anything is dropped.
     *
     * @param backupContent the gzip'd backup file contents
     * @param confirmed      the admin must have confirmed replacement on the form
     * @throws NameNotAllowedException  when the file is malformed or confirmation
     *                                  is missing
     * @throws ProvisioningException    when a MongoDB operation fails mid-restore
     */
    public RestoreResult restore(String dbName, byte[] backupContent, boolean confirmed) {
        nameValidator.validateDatabaseName(dbName);
        if (!confirmed) {
            throw new NameNotAllowedException("Restore requires confirmation that existing data may be replaced");
        }
        // Parsing is a pure function of the file content - run it before taking
        // the per-database lock so a malformed upload never blocks other admins.
        List<BackupCollection> collections = readBackup(backupContent);

        // The drop-then-recreate sequence is a check-then-act span; hold the same
        // per-database lock as the provisioning lifecycle so a concurrent
        // delete/provision cannot interleave (e.g. resurrecting a just-dropped
        // database as a zombie with no metadata or user).
        return databaseLocks.withLock(dbName, () -> {
            try {
                if (mongoDatabaseRepository.databaseExists(dbName)) {
                    for (String existing : mongoDatabaseRepository.listCollectionNames(dbName)) {
                        mongoDatabaseRepository.dropCollection(dbName, existing);
                    }
                }
                long totalDocuments = 0;
                for (BackupCollection collection : collections) {
                    mongoDatabaseRepository.createCollection(dbName, collection.name());
                    recreateIndexes(dbName, collection);
                    List<Document> documents = collection.documents();
                    totalDocuments += documents.size();
                    for (int i = 0; i < documents.size(); i += INSERT_BATCH_SIZE) {
                        mongoDatabaseRepository.insertDocuments(dbName, collection.name(),
                                documents.subList(i, Math.min(i + INSERT_BATCH_SIZE, documents.size())));
                    }
                }
                audit(AuditEvent.BACKUP_RESTORED, dbName, clock.instant());
                log.info("Restored database '{}': {} collections, {} documents",
                        dbName, collections.size(), totalDocuments);
                return new RestoreResult(dbName, collections.size(), totalDocuments);
            } catch (MongoException e) {
                log.error("Failed to restore database '{}'", dbName, e);
                throw new ProvisioningException("Could not restore database '" + dbName + "'", e);
            }
        });
    }

    /**
     * Parses and structurally validates a backup file into collection plans.
     * Runs before any destructive step so a malformed file never leaves the
     * database partially replaced.
     */
    private List<BackupCollection> readBackup(byte[] content) {
        Document backup;
        try {
            String json;
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
                json = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
            backup = Document.parse(json);
        } catch (IOException | RuntimeException e) {
            throw new NameNotAllowedException("Backup file could not be read or is not a valid backup");
        }

        Integer formatVersion;
        List<Document> collectionDocs;
        try {
            formatVersion = backup.getInteger("formatVersion");
            collectionDocs = backup.getList("collections", Document.class);
        } catch (RuntimeException e) {
            throw new NameNotAllowedException("Backup file is not a valid backup");
        }
        if (formatVersion == null || formatVersion != FORMAT_VERSION) {
            throw new NameNotAllowedException("Unsupported backup format version: " + formatVersion);
        }
        if (collectionDocs == null) {
            throw new NameNotAllowedException("Backup file does not contain any collections");
        }

        List<BackupCollection> collections = new ArrayList<>(collectionDocs.size());
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (Document collectionDoc : collectionDocs) {
            String name;
            List<Document> indexes;
            List<Document> documents;
            try {
                name = collectionDoc.getString("name");
                indexes = collectionDoc.getList("indexes", Document.class);
                documents = collectionDoc.getList("documents", Document.class);
                if (indexes != null) {
                    for (Document index : indexes) {
                        Object unique = index.get("unique");
                        if (!(index.get("key") instanceof Document) || index.getString("name") == null
                                || (unique != null && !(unique instanceof Boolean))) {
                            throw new NameNotAllowedException("Backup file is not a valid backup");
                        }
                    }
                }
            } catch (RuntimeException e) {
                throw new NameNotAllowedException("Backup file is not a valid backup");
            }
            if (name == null || name.isBlank()) {
                throw new NameNotAllowedException("Backup contains a collection without a name");
            }
            nameValidator.validateCollectionName(name);
            if (!seenNames.add(name)) {
                throw new NameNotAllowedException("Backup contains collection '" + name + "' more than once");
            }
            collections.add(new BackupCollection(name,
                    indexes == null ? List.of() : indexes,
                    documents == null ? List.of() : documents));
        }
        return collections;
    }

    private String indexesToJson(String dbName, String collectionName) {
        List<Document> rawIndexes = mongoDatabaseRepository.listCollectionIndexes(dbName, collectionName);
        return rawIndexes.stream()
                .map(index -> new Document("key", index.get("key"))
                        .append("name", index.getString("name"))
                        .append("unique", index.get("unique", false))
                        .toJson(CANONICAL))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private void recreateIndexes(String dbName, BackupCollection collection) {
        for (Document index : collection.indexes()) {
            Document keys = index.get("key", Document.class);
            String name = index.getString("name");
            if (keys == null || name == null) {
                log.warn("Skipping malformed index entry in backup of {}.{}", dbName, collection.name());
                continue;
            }
            if ("_id_".equals(name)) {
                continue; // implicit index, recreated by createCollection
            }
            boolean unique = Boolean.TRUE.equals(index.getBoolean("unique"));
            mongoDatabaseRepository.createIndex(dbName, collection.name(), keys, unique);
        }
    }

    private void audit(String eventType, String dbName, Instant performedAt) {
        AuditEvent event = new AuditEvent(eventType, dbName, com.pkmprojects.mongodbserver.model.DatabaseEngineType.MONGO, null, currentUsername(), performedAt);
        auditStore.save(event);
        applicationEventPublisher.publishEvent(new AuditEventRecorded(event));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null ? authentication.getName() : "unknown";
    }

    /**
     * Summary of a completed backup download.
     */
    public record BackupResult(String dbName, int collectionCount, long documentCount) {
    }

    /**
     * Summary of a completed restore.
     */
    public record RestoreResult(String dbName, int collectionsRestored, long documentsRestored) {
    }

    /**
     * Data shown on the restore form (the database may not exist yet).
     */
    public record DatabaseBackupInfo(String dbName, boolean exists, int collectionCount) {
    }

    /**
     * One validated collection found in a backup file, with everything resolved
     * to concrete lists (missing entries become empty lists).
     */
    private record BackupCollection(String name, List<Document> indexes, List<Document> documents) {
    }

    /**
     * Wraps an {@link IOException} escaping the streaming document consumer,
     * where checked exceptions are not allowed.
     */
    private static class BackupWriteException extends RuntimeException {
        BackupWriteException(IOException cause) {
            super(cause);
        }
    }

    private static final byte[] COMMA = ",".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COLLECTION_SUFFIX = "]}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOCUMENT_ROOT_SUFFIX = "]}".getBytes(StandardCharsets.UTF_8);
}
