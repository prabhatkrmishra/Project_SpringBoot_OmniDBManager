package com.pkmprojects.mongodbserver.config;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Creates the descending index backing the audit-trail queries
 * ({@code findTop10ByOrderByPerformedAtDesc}, the activity page) so the trail
 * stays fast as it grows. Idempotent: MongoDB treats re-creating an identical
 * index as a no-op.
 */
@Component
public class MongoIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private static final String DEFAULT_METADATA_DATABASE = "mongodb_admin";

    private final MongoClient mongoClient;
    private final Environment environment;

    public MongoIndexInitializer(MongoClient mongoClient, Environment environment) {
        this.mongoClient = mongoClient;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String metadataDatabase = environment.getProperty("spring.mongodb.database", DEFAULT_METADATA_DATABASE);
        try {
            mongoClient.getDatabase(metadataDatabase)
                    .getCollection("admin_activity")
                    .createIndex(new Document("performedAt", -1));
            log.info("Ensured index on {}.admin_activity(performedAt)", metadataDatabase);
            mongoClient.getDatabase(metadataDatabase)
                    .getCollection("provisioned_databases")
                    .createIndex(new Document("engineType", 1).append("dbName", 1),
                            new com.mongodb.client.model.IndexOptions().unique(true));
            log.info("Ensured unique index on {}.provisioned_databases(engineType, dbName)", metadataDatabase);
            mongoClient.getDatabase(metadataDatabase)
                    .getCollection("provisioned_databases")
                    .createIndex(new Document("engineType", 1));
            mongoClient.getDatabase(metadataDatabase)
                    .getCollection("admin_activity")
                    .createIndex(new Document("engineType", 1));
        } catch (MongoException e) {
            // MongoDB unreachable (or index creation refused): log and continue so
            // the login page and read endpoints still come up during a Mongo outage;
            // audit queries degrade to full scans until the next successful boot.
            log.warn("Could not create audit index on {}.admin_activity(performedAt): {}", metadataDatabase, e.getMessage());
        }
    }
}
