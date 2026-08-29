package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.dto.CollectionInfo;
import com.pkmprojects.mongodbserver.dto.DocumentPage;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only exploration: collections of a database and paginated documents of a
 * collection. No business rules - the validator guards names, pagination keeps
 * queries bounded.
 */
@Service
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class ExplorationService {

    /**
     * Page size for the document explorer and the JSON export.
     */
    static final int DEFAULT_PAGE_SIZE = 50;
    private static final Logger log = LoggerFactory.getLogger(ExplorationService.class);
    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final MongoNameValidator nameValidator;

    public ExplorationService(MongoDatabaseRepository mongoDatabaseRepository, MongoNameValidator nameValidator) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.nameValidator = nameValidator;
    }

    /**
     * @return the collections of {@code dbName} with their document counts
     * @throws DatabaseNotFoundException when the database does not exist
     */
    public List<CollectionInfo> listCollections(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        requireDatabase(dbName);
        return mongoDatabaseRepository.listCollectionNames(dbName).stream()
                .map(collection -> new CollectionInfo(collection, countDocuments(dbName, collection)))
                .toList();
    }

    /**
     * Returns one page of documents of a collection, rendered as extended JSON.
     * Pages are 1-based; out-of-range pages clamp to the first/last page.
     *
     * @throws DatabaseNotFoundException when the database or collection does not exist
     */
    public DocumentPage getDocuments(String dbName, String collectionName, int page) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateCollectionName(collectionName);
        requireDatabase(dbName);
        if (!mongoDatabaseRepository.collectionExists(dbName, collectionName)) {
            throw new DatabaseNotFoundException("Collection '" + collectionName + "' does not exist");
        }

        long totalCount = countDocuments(dbName, collectionName);
        int totalPages = (int) Math.ceil((double) totalCount / DEFAULT_PAGE_SIZE);
        int safePage = Math.max(1, Math.min(page, Math.max(totalPages, 1)));
        int skip = (safePage - 1) * DEFAULT_PAGE_SIZE;

        List<String> documents = mongoDatabaseRepository.findDocuments(dbName, collectionName, skip, DEFAULT_PAGE_SIZE)
                .stream()
                .map(Document::toJson)
                .toList();

        return new DocumentPage(dbName, collectionName, safePage, DEFAULT_PAGE_SIZE, totalCount, totalPages,
                documents, safePage > 1, safePage < totalPages);
    }

    /**
     * One page of a collection as a JSON array for download. Reuses the same
     * pagination and validation as the explorer; the page is bounded to
     * {@value #DEFAULT_PAGE_SIZE} documents.
     */
    public String exportDocumentsAsJson(String dbName, String collectionName, int page) {
        DocumentPage documentPage = getDocuments(dbName, collectionName, page);
        return documentPage.documents().stream().collect(Collectors.joining(",", "[", "]"));
    }

    private void requireDatabase(String dbName) {
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
    }

    private long countDocuments(String dbName, String collectionName) {
        try {
            return mongoDatabaseRepository.countDocuments(dbName, collectionName);
        } catch (MongoException e) {
            log.warn("Could not count documents in {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not read collection '" + collectionName + "'", e);
        }
    }
}
