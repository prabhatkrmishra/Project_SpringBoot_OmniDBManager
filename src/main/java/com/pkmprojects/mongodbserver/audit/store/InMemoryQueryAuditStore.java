package com.pkmprojects.mongodbserver.audit.store;

import com.pkmprojects.mongodbserver.audit.QueryAuditEvent;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bounded in-memory {@code query_audit} fallback when Mongo is disabled.
 * Ephemeral: cleared on restart. Oldest entries are evicted past the bound so
 * memory cannot grow without limit.
 */
@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryQueryAuditStore implements QueryAuditStore {

    public static final int MAX_RETAINED = 2000;

    private final CopyOnWriteArrayList<QueryAuditEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();

    @Override
    public void record(QueryAuditEvent event) {
        if (event == null) {
            dropped.incrementAndGet();
            return;
        }
        events.add(event);
        while (events.size() > MAX_RETAINED) {
            events.remove(0);
            dropped.incrementAndGet();
        }
    }

    @Override
    public List<QueryAuditEvent> findFiltered(QueryAuditFilter filter, int skip, int limit) {
        return filtered(filter)
                .sorted(Comparator.comparing(QueryAuditEvent::getObservedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .skip(Math.max(0, skip)).limit(Math.max(0, limit)).toList();
    }

    @Override
    public long countFiltered(QueryAuditFilter filter) {
        return filtered(filter).count();
    }

    @Override
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public Optional<Instant> latestObservedAt() {
        return events.stream().map(QueryAuditEvent::getObservedAt)
                .filter(v -> v != null).max(Comparator.naturalOrder());
    }

    private Stream<QueryAuditEvent> filtered(QueryAuditFilter filter) {
        Stream<QueryAuditEvent> s = events.stream();
        if (filter == null) {
            return s;
        }
        if (filter.engine() != null && !filter.engine().isBlank()) {
            String eng = filter.engine().trim();
            s = s.filter(e -> e.getEngine() != null && eng.equalsIgnoreCase(e.getEngine().name()));
        }
        if (filter.databaseContains() != null && !filter.databaseContains().isBlank()) {
            String needle = filter.databaseContains().trim().toLowerCase();
            s = s.filter(e -> e.getDatabase() != null && e.getDatabase().toLowerCase().contains(needle));
        }
        if (filter.userContains() != null && !filter.userContains().isBlank()) {
            String needle = filter.userContains().trim().toLowerCase();
            s = s.filter(e -> e.getProvisionedUser() != null && e.getProvisionedUser().toLowerCase().contains(needle));
        }
        if (filter.sourceIp() != null && !filter.sourceIp().isBlank()) {
            String ip = filter.sourceIp().trim();
            s = s.filter(e -> ip.equals(e.getSourceIp()));
        }
        if (filter.operationClass() != null && !filter.operationClass().isBlank()) {
            String oc = filter.operationClass().trim();
            s = s.filter(e -> oc.equalsIgnoreCase(e.getOperationClass()));
        }
        if (filter.success() != null) {
            Boolean want = filter.success();
            s = s.filter(e -> want.equals(e.getSuccess()));
        }
        if (filter.attribution() != null && !filter.attribution().isBlank()) {
            String at = filter.attribution().trim();
            s = s.filter(e -> e.getAttribution() != null && at.equalsIgnoreCase(e.getAttribution().name()));
        }
        if (filter.confidence() != null && !filter.confidence().isBlank()) {
            String cf = filter.confidence().trim();
            s = s.filter(e -> e.getAuditConfidence() != null && cf.equalsIgnoreCase(e.getAuditConfidence().name()));
        }
        if (filter.shapeHash() != null && !filter.shapeHash().isBlank()) {
            String h = filter.shapeHash().trim();
            s = s.filter(e -> h.equalsIgnoreCase(e.getShapeHash()));
        }
        if (filter.from() != null) {
            Instant from = filter.from();
            s = s.filter(e -> e.getObservedAt() != null && !e.getObservedAt().isBefore(from));
        }
        if (filter.to() != null) {
            Instant to = filter.to();
            s = s.filter(e -> e.getObservedAt() != null && !e.getObservedAt().isAfter(to));
        }
        return s;
    }
}
