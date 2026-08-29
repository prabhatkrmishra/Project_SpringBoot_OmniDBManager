package com.pkmprojects.mongodbserver.store;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import org.springframework.data.domain.Sort;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Adapts the Mongo {@link AuditLogRepository} to the {@link AuditStore}
 * contract. Used by legacy test constructors that inject the repository
 * directly; production wiring injects {@code MongoAuditStore} or
 * {@code InMemoryAuditStore} via {@code AuditStore}.
 */
public class AuditLogRepositoryAdapter implements AuditStore {

    private final AuditLogRepository repo;

    public AuditLogRepositoryAdapter(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Override
    public AuditEvent save(AuditEvent event) {
        return repo.save(event);
    }

    @Override
    public List<AuditEvent> findTop10ByOrderByPerformedAtDesc() {
        return repo.findTop10ByOrderByPerformedAtDesc();
    }

    @Override
    public List<AuditEvent> findAll(Sort sort) {
        return repo.findAll(sort);
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
        Comparator<AuditEvent> cmp = Comparator.comparing(AuditEvent::getPerformedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
        return filteredStream(eventType, engineType, dbNameContains, userNameContains, performedByContains)
                .sorted(cmp).skip(skip).limit(limit).toList();
    }

    private Stream<AuditEvent> filteredStream(String eventType, String engineType,
                                              String dbNameContains, String userNameContains, String performedByContains) {
        Stream<AuditEvent> s = repo.findAll(Sort.unsorted()).stream();
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