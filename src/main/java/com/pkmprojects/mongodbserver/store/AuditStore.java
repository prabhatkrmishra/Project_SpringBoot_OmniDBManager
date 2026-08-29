package com.pkmprojects.mongodbserver.store;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * Abstraction over audit trail storage so Postgres/MySQL provisioning
 * can work without MongoDB. Mongo-backed when {@code app.mongo.enabled=true},
 * in-memory when disabled.
 */
public interface AuditStore {

    AuditEvent save(AuditEvent event);

    List<AuditEvent> findTop10ByOrderByPerformedAtDesc();

    List<AuditEvent> findAll(Sort sort);

    long countFiltered(String eventType, String engineType,
                       String dbNameContains, String userNameContains, String performedByContains);

    List<AuditEvent> findFiltered(String eventType, String engineType,
                                  String dbNameContains, String userNameContains, String performedByContains,
                                  int skip, int limit, Sort sort);
}
