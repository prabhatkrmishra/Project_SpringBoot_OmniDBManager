package com.pkmprojects.mongodbserver.repository;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Read access to the admin activity audit trail. Only loaded when mongo enabled.
 */
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public interface AuditLogRepository extends MongoRepository<AuditEvent, String> {

    /**
     * @return the 10 most recent audit events, newest first
     */
    List<AuditEvent> findTop10ByOrderByPerformedAtDesc();
}
