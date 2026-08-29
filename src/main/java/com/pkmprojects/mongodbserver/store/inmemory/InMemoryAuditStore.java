package com.pkmprojects.mongodbserver.store.inmemory;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.store.AuditStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * In-memory audit store when MongoDB is disabled. Ephemeral — cleared on restart.
 */
@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryAuditStore implements AuditStore {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public AuditEvent save(AuditEvent event) {
        events.add(event);
        return event;
    }

    @Override
    public List<AuditEvent> findTop10ByOrderByPerformedAtDesc() {
        return events.stream()
                .sorted(Comparator.comparing(AuditEvent::getPerformedAt).reversed())
                .limit(10)
                .toList();
    }

    @Override
    public List<AuditEvent> findAll(Sort sort) {
        Comparator<AuditEvent> cmp = Comparator.comparing(AuditEvent::getPerformedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
        List<AuditEvent> copy = events.stream().sorted(cmp).toList();
        if (sort != null && sort.getOrderFor("performedAt") != null && sort.getOrderFor("performedAt").isDescending()) {
            copy = copy.reversed();
        } else if (sort != null && sort.getOrderFor("createdAt") != null) {
            // legacy callers that sort by createdAt - treat as performedAt
            if (sort.getOrderFor("createdAt").isDescending()) {
                copy = copy.reversed();
            }
        }
        return copy;
    }

    @Override
    public long countFiltered(String eventType, String engineType,
                              String dbNameContains, String userNameContains, String performedByContains) {
        return filteredStream(eventType, engineType, dbNameContains, userNameContains, performedByContains).count();
    }

    @Override
    public List<AuditEvent> findFiltered(String eventType, String engineType,
                                         String dbNameContains, String userNameContains, String performedByContains,
                                         int skip, int limit, Sort sort) {
        Stream<AuditEvent> s = filteredStream(eventType, engineType, dbNameContains, userNameContains, performedByContains);
        Comparator<AuditEvent> cmp = Comparator.comparing(AuditEvent::getPerformedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
        // Sort param is always performedAt DESC in ActivityController
        s = s.sorted(cmp);
        return s.skip(skip).limit(limit).toList();
    }

    private Stream<AuditEvent> filteredStream(String eventType, String engineType,
                                              String dbNameContains, String userNameContains, String performedByContains) {
        Stream<AuditEvent> s = events.stream();
        if (eventType != null && !eventType.isBlank()) {
            String et = eventType.trim();
            s = s.filter(e -> et.equals(e.getEventType()));
        }
        if (engineType != null && !engineType.isBlank()) {
            String eng = engineType.trim();
            if ("MONGO".equalsIgnoreCase(eng)) {
                s = s.filter(e -> e.getEngineType() == null || "MONGO".equals(e.getEngineType().name()));
            } else {
                s = s.filter(e -> e.getEngineType() != null && eng.equalsIgnoreCase(e.getEngineType().name()));
            }
        }
        if (dbNameContains != null && !dbNameContains.isBlank()) {
            String needle = dbNameContains.trim().toLowerCase();
            Pattern p = Pattern.compile(".*" + Pattern.quote(needle) + ".*", Pattern.CASE_INSENSITIVE);
            s = s.filter(e -> e.getDbName() != null && p.matcher(e.getDbName()).matches());
        }
        if (userNameContains != null && !userNameContains.isBlank()) {
            String needle = userNameContains.trim().toLowerCase();
            Pattern p = Pattern.compile(".*" + Pattern.quote(needle) + ".*", Pattern.CASE_INSENSITIVE);
            s = s.filter(e -> e.getUserName() != null && p.matcher(e.getUserName()).matches());
        }
        if (performedByContains != null && !performedByContains.isBlank()) {
            String needle = performedByContains.trim().toLowerCase();
            Pattern p = Pattern.compile(".*" + Pattern.quote(needle) + ".*", Pattern.CASE_INSENSITIVE);
            s = s.filter(e -> e.getPerformedBy() != null && p.matcher(e.getPerformedBy()).matches());
        }
        return s;
    }
}
