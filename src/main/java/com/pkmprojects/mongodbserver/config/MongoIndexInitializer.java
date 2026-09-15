package com.pkmprojects.mongodbserver.config;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Creates the descending index backing the audit-trail queries
 * ({@code findTop10ByOrderByPerformedAtDesc}, the activity page) so the trail
 * stays fast as it grows. Idempotent: MongoDB treats re-creating an identical
 * index as a no-op. Only loaded when {@code app.mongo.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private static final String DEFAULT_METADATA_DATABASE = "mongodb_admin";

    private final MongoClient mongoClient;
    private final Environment environment;

    public MongoIndexInitializer(@Autowired(required = false) MongoClient mongoClient, Environment environment) {
        this.mongoClient = mongoClient;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mongoClient == null) {
            log.warn("MongoIndexInitializer skipped: MongoClient unavailable");
            return;
        }
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
            ensureQueryAuditIndexes(mongoClient, metadataDatabase);
            applyQueryAuditTtl(mongoClient, metadataDatabase, environment);
        } catch (MongoException e) {
            // MongoDB unreachable (or index creation refused): log and continue so
            // the login page and read endpoints still come up during a Mongo outage;
            // audit queries degrade to full scans until the next successful boot.
            log.warn("Could not create audit index on {}.admin_activity(performedAt): {}", metadataDatabase, e.getMessage());
        }
    }

    /**
     * Indexes backing the operator query-activity view. The TTL index enforces
     * the operator-selected retention on {@code observedAt}; all other indexes
     * serve the filter combinations on the query-activity page and API.
     */
    private static void ensureQueryAuditIndexes(com.mongodb.client.MongoClient client, String db) {
        var collection = client.getDatabase(db).getCollection("query_audit");
        collection.createIndex(new Document("observedAt", -1));
        collection.createIndex(new Document("engine", 1).append("database", 1).append("observedAt", -1));
        collection.createIndex(new Document("provisionedUser", 1).append("observedAt", -1));
        collection.createIndex(new Document("sourceIp", 1).append("observedAt", -1));
        collection.createIndex(new Document("shapeHash", 1));
        collection.createIndex(new Document("attribution", 1).append("auditConfidence", 1));
        log.info("Ensured indexes on {}.query_audit", db);
    }

    /**
     * Applies the TTL retention on {@code query_audit.observedAt}. The TTL
     * index is recreated when the configured retention changes; creation is
     * idempotent otherwise.
     */
    private static void applyQueryAuditTtl(com.mongodb.client.MongoClient client, String db,
                                           org.springframework.core.env.Environment environment) {
        int days = 30;
        try {
            String raw = environment.getProperty("app.query-audit.retention-days", "30");
            days = Math.max(1, Math.min(Integer.parseInt(raw.trim()), 365));
        } catch (Exception ignored) {
            days = 30;
        }
        long seconds = (long) days * 24 * 60 * 60;
        var collection = client.getDatabase(db).getCollection("query_audit");
        for (var index : collection.listIndexes()) {
            if ("query_audit_observedAt_ttl".equals(index.getString("name"))) {
                Object current = index.get("expireAfterSeconds");
                if (current instanceof Number n && n.longValue() == seconds) {
                    return;
                }
                collection.dropIndex("query_audit_observedAt_ttl");
                break;
            }
        }
        collection.createIndex(new Document("observedAt", 1),
                new com.mongodb.client.model.IndexOptions()
                        .expireAfter(seconds, java.util.concurrent.TimeUnit.SECONDS)
                        .name("query_audit_observedAt_ttl"));
        log.info("Ensured TTL on {}.query_audit(observedAt, {} days)", db, days);
    }
}
