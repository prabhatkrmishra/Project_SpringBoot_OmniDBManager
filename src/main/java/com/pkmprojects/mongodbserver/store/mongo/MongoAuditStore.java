package com.pkmprojects.mongodbserver.store.mongo;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.store.AuditStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoAuditStore implements AuditStore {

    private final AuditLogRepository auditRepo;
    private final MongoTemplate mongoTemplate;

    public MongoAuditStore(AuditLogRepository auditRepo, MongoTemplate mongoTemplate) {
        this.auditRepo = auditRepo;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public AuditEvent save(AuditEvent event) {
        return auditRepo.save(event);
    }

    @Override
    public List<AuditEvent> findTop10ByOrderByPerformedAtDesc() {
        return auditRepo.findTop10ByOrderByPerformedAtDesc();
    }

    @Override
    public List<AuditEvent> findAll(Sort sort) {
        return auditRepo.findAll(sort);
    }

    @Override
    public long countFiltered(String eventType, String engineType,
                              String dbNameContains, String userNameContains, String performedByContains) {
        return mongoTemplate.count(buildQuery(eventType, engineType, dbNameContains, userNameContains, performedByContains), AuditEvent.class);
    }

    @Override
    public List<AuditEvent> findFiltered(String eventType, String engineType,
                                         String dbNameContains, String userNameContains, String performedByContains,
                                         int skip, int limit, Sort sort) {
        Query q = buildQuery(eventType, engineType, dbNameContains, userNameContains, performedByContains);
        q.with(PageRequest.of(skip / Math.max(1, limit), limit, sort));
        // PageRequest uses page index; we need exact skip
        q.skip(skip);
        q.limit(limit);
        q.with(sort);
        return mongoTemplate.find(q, AuditEvent.class);
    }

    private Query buildQuery(String eventType, String engineType, String dbName, String userName, String performedBy) {
        List<Criteria> criteria = new ArrayList<>();
        if (eventType != null && !eventType.isBlank()) criteria.add(Criteria.where("eventType").is(eventType.trim()));
        if (engineType != null && !engineType.isBlank()) {
            String t = engineType.trim();
            if ("MONGO".equalsIgnoreCase(t)) {
                criteria.add(new Criteria().orOperator(
                        Criteria.where("engineType").is("MONGO"),
                        Criteria.where("engineType").exists(false),
                        Criteria.where("engineType").is(null)));
            } else {
                criteria.add(Criteria.where("engineType").is(t));
            }
        }
        if (dbName != null && !dbName.isBlank()) criteria.add(Criteria.where("dbName").regex(contains(dbName.trim()), "i"));
        if (userName != null && !userName.isBlank()) criteria.add(Criteria.where("userName").regex(contains(userName.trim()), "i"));
        if (performedBy != null && !performedBy.isBlank()) criteria.add(Criteria.where("performedBy").regex(contains(performedBy.trim()), "i"));
        if (criteria.isEmpty()) return new Query();
        return new Query(Criteria.where("id").exists(true).andOperator(criteria.toArray(new Criteria[0])));
    }

    private static String contains(String input) {
        return ".*" + Pattern.quote(input) + ".*";
    }
}
