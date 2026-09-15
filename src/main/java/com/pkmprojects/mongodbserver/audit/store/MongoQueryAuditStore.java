package com.pkmprojects.mongodbserver.audit.store;

import com.pkmprojects.mongodbserver.audit.QueryAuditEvent;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Mongo-backed {@code query_audit} store. Fail-open: persistence failures are
 * counted and logged, never thrown into tenant or operator paths.
 */
@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoQueryAuditStore implements QueryAuditStore {

    private static final Logger log = LoggerFactory.getLogger(MongoQueryAuditStore.class);

    private final MongoTemplate mongoTemplate;
    private final AtomicLong dropped = new AtomicLong();

    public MongoQueryAuditStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void record(QueryAuditEvent event) {
        if (event == null) {
            dropped.incrementAndGet();
            return;
        }
        try {
            mongoTemplate.save(event);
        } catch (Exception e) {
            dropped.incrementAndGet();
            log.warn("query_audit persist failed (fail-open, event dropped): {}", e.getMessage());
        }
    }

    @Override
    public List<QueryAuditEvent> findFiltered(QueryAuditFilter filter, int skip, int limit) {
        Query q = buildQuery(filter);
        q.with(Sort.by(Sort.Direction.DESC, "observedAt"));
        q.skip(Math.max(0, skip));
        q.limit(Math.max(1, Math.min(limit, 200)));
        try {
            return mongoTemplate.find(q, QueryAuditEvent.class);
        } catch (Exception e) {
            log.warn("query_audit read failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public long countFiltered(QueryAuditFilter filter) {
        try {
            return mongoTemplate.count(buildQuery(filter), QueryAuditEvent.class);
        } catch (Exception e) {
            log.warn("query_audit count failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public Optional<Instant> latestObservedAt() {
        try {
            Query q = new Query().with(Sort.by(Sort.Direction.DESC, "observedAt")).limit(1);
            QueryAuditEvent latest = mongoTemplate.findOne(q, QueryAuditEvent.class);
            return latest == null || latest.getObservedAt() == null
                    ? Optional.empty() : Optional.of(latest.getObservedAt());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Query buildQuery(QueryAuditFilter filter) {
        Query q = new Query();
        if (filter == null) {
            return q;
        }
        if (filter.engine() != null && !filter.engine().isBlank()) {
            q.addCriteria(Criteria.where("engine").is(filter.engine().trim().toUpperCase()));
        }
        if (filter.databaseContains() != null && !filter.databaseContains().isBlank()) {
            q.addCriteria(Criteria.where("database").regex(".*" + Pattern.quote(filter.databaseContains().trim()) + ".*", "i"));
        }
        if (filter.userContains() != null && !filter.userContains().isBlank()) {
            q.addCriteria(Criteria.where("provisionedUser").regex(".*" + Pattern.quote(filter.userContains().trim()) + ".*", "i"));
        }
        if (filter.sourceIp() != null && !filter.sourceIp().isBlank()) {
            q.addCriteria(Criteria.where("sourceIp").is(filter.sourceIp().trim()));
        }
        if (filter.operationClass() != null && !filter.operationClass().isBlank()) {
            q.addCriteria(Criteria.where("operationClass").is(filter.operationClass().trim().toLowerCase()));
        }
        if (filter.success() != null) {
            q.addCriteria(Criteria.where("success").is(filter.success()));
        }
        if (filter.attribution() != null && !filter.attribution().isBlank()) {
            q.addCriteria(Criteria.where("attribution").is(filter.attribution().trim().toUpperCase()));
        }
        if (filter.confidence() != null && !filter.confidence().isBlank()) {
            q.addCriteria(Criteria.where("auditConfidence").is(filter.confidence().trim().toUpperCase()));
        }
        if (filter.shapeHash() != null && !filter.shapeHash().isBlank()) {
            q.addCriteria(Criteria.where("shapeHash").is(filter.shapeHash().trim().toLowerCase()));
        }
        if (filter.from() != null || filter.to() != null) {
            Criteria c = Criteria.where("observedAt");
            if (filter.from() != null) {
                c = c.gte(filter.from());
            }
            if (filter.to() != null) {
                c = c.lte(filter.to());
            }
            q.addCriteria(c);
        }
        return q;
    }
}
