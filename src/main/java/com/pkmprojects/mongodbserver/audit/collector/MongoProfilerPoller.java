package com.pkmprojects.mongodbserver.audit.collector;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuous poller over per-tenant {@code system.profile} collections.
 *
 * <p>Semantics (MongoDB Community): {@code system.profile} is a capped
 * collection — old records roll over and disappear; it is NOT an infinite
 * append-only stream. The poller therefore queries {@code ts > lastAccepted}
 * newest-first-bounded per database per poll, advances the resume marker to
 * the maximum accepted {@code ts}, and tolerates gaps caused by rollover
 * (logged at debug; dedupe downstream absorbs replays).</p>
 *
 * <p>Scope: only databases present in the provisioned-metadata store for the
 * MONGO engine; {@code admin/local/config} are never polled. Each poll reads
 * at most {@value #MAX_DOCS_PER_POLL} docs per database, each doc capped by
 * the driver's 16MB document limit (profiler docs are small in practice;
 * oversized shapes are truncated by the command-shape renderer).</p>
 */
public class MongoProfilerPoller {

    private static final Logger log = LoggerFactory.getLogger(MongoProfilerPoller.class);

    static final int MAX_DOCS_PER_POLL = 200;
    private static final Set<String> SYSTEM_DBS = Set.of("admin", "local", "config");

    private final MongoClient mongoClient;
    private final CollectorOffsetStore offsets;

    public MongoProfilerPoller(MongoClient mongoClient, CollectorOffsetStore offsets) {
        this.mongoClient = mongoClient;
        this.offsets = offsets;
    }

    /**
     * Polls one tenant database's profiler. Returns docs accepted (newest
     * last). Never throws: driver errors yield zero and are logged at debug.
     */
    public List<Document> pollDatabase(String dbName, Consumer<Document> docs) {
        List<Document> accepted = new ArrayList<>();
        if (mongoClient == null || dbName == null || dbName.isBlank() || SYSTEM_DBS.contains(dbName)) {
            return accepted;
        }
        try {
            Instant since = offsets == null ? null
                    : offsets.profilerTs(dbName).orElse(null);
            Bson filter = since == null ? new Document()
                    : Filters.gt("ts", Date.from(since));
            List<Document> batch = new ArrayList<>(MAX_DOCS_PER_POLL);
            mongoClient.getDatabase(dbName).getCollection("system.profile")
                    .find(filter).sort(Sorts.ascending("ts")).limit(MAX_DOCS_PER_POLL)
                    .forEach(batch::add);
            Instant max = since;
            for (Document doc : batch) {
                docs.accept(doc);
                accepted.add(doc);
                Object ts = doc.get("ts");
                if (ts instanceof Date d && (max == null || d.toInstant().isAfter(max))) {
                    max = d.toInstant();
                }
            }
            if (max != null && offsets != null) {
                offsets.saveProfilerTs(dbName, max);
            }
        } catch (Exception e) {
            // Profiler absent (level 0 / collection missing) or permissions:
            // not an error — that database simply yields nothing this poll.
            log.debug("profiler poll {} skipped: {}", dbName, e.getMessage());
        }
        return accepted;
    }
}
