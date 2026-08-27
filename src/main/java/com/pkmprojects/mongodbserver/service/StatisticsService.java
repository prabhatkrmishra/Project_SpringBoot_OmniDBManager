package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.dto.CollectionStats;
import com.pkmprojects.mongodbserver.dto.DatabaseStats;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only database statistics: aggregate {@code dbStats} plus per-collection
 * {@code collStats}. No business rules - the validator guards names and each
 * collection is read with a single bounded command. Per-collection commands
 * run concurrently (bounded) so a database with many collections does not
 * serialize into N sequential round trips.
 */
@Service
public class StatisticsService {

    private static final Logger log = LoggerFactory.getLogger(StatisticsService.class);

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final MongoNameValidator nameValidator;

    public StatisticsService(MongoDatabaseRepository mongoDatabaseRepository, MongoNameValidator nameValidator) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.nameValidator = nameValidator;
    }

    /**
     * @return aggregate and per-collection statistics for {@code dbName}
     * @throws DatabaseNotFoundException when the database does not exist
     * @throws ProvisioningException     when {@code dbStats}/{@code collStats} cannot be read
     */
    public DatabaseStats getDatabaseStats(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        requireDatabase(dbName);

        Document stats;
        try {
            stats = mongoDatabaseRepository.getDbStats(dbName);
        } catch (MongoException e) {
            log.warn("Could not read dbStats for {}", dbName, e);
            throw new ProvisioningException("Could not read statistics for database '" + dbName + "'", e);
        }

        List<CollectionStats> collections = parallelCollectionStats(dbName,
                mongoDatabaseRepository.listCollectionNames(dbName));

        return new DatabaseStats(
                dbName,
                intValue(stats.get("collections")),
                intValue(stats.get("views")),
                longValue(stats.get("objects")),
                longValue(stats.get("dataSize")),
                longValue(stats.get("storageSize")),
                longValue(stats.get("avgObjSize")),
                intValue(stats.get("indexes")),
                longValue(stats.get("indexSize")),
                collections);
    }

    /**
     * Reads {@code collStats} for every collection, issuing the commands
     * concurrently on a bounded executor. A single collection (or none) is read
     * inline to avoid executor overhead.
     */
    private List<CollectionStats> parallelCollectionStats(String dbName, List<String> collectionNames) {
        if (collectionNames.size() <= 1) {
            return collectionNames.stream().map(name -> collectionStats(dbName, name)).toList();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<CollectionStats>> futures = collectionNames.stream()
                    .map(name -> CompletableFuture.supplyAsync(() -> collectionStats(dbName, name), executor))
                    .toList();
            return futures.stream().map(StatisticsService::joinUnwrapping).toList();
        }
    }

    private CollectionStats collectionStats(String dbName, String collectionName) {
        Document stats;
        try {
            stats = mongoDatabaseRepository.getCollectionStats(dbName, collectionName);
        } catch (MongoException e) {
            log.warn("Could not read collStats for {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not read statistics for collection '" + collectionName + "'", e);
        }
        return new CollectionStats(
                collectionName,
                longValue(stats.get("count")),
                longValue(stats.get("size")),
                longValue(stats.get("storageSize")),
                longValue(stats.get("avgObjSize")),
                intValue(stats.get("nindexes")),
                longValue(stats.get("totalIndexSize")));
    }

    /**
     * {@link CompletableFuture#join()} unwraps the {@link CompletionException}
     * so the original {@link ProvisioningException} reaches the error handler.
     */
    private static <T> T joinUnwrapping(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private void requireDatabase(String dbName) {
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}