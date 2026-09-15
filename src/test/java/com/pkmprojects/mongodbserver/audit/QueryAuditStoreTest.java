package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import com.pkmprojects.mongodbserver.audit.store.InMemoryQueryAuditStore;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Store/filter contract for the operator query-activity view.
 */
class QueryAuditStoreTest {

    private QueryAuditEvent event(DatabaseEngineType engine, String db, String user, String ip,
                                  String opClass, QueryAttribution attribution) {
        QueryAuditEvent e = new QueryAuditEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setObservedAt(Instant.now());
        e.setEngine(engine);
        e.setDatabase(db);
        e.setManagedDatabaseId(engine.name() + ":" + db);
        e.setProvisionedUser(user);
        e.setSourceIp(ip);
        e.setOperationClass(opClass);
        e.setCommandType("select");
        e.setNormalizedShape("SELECT * FROM t WHERE id = ?");
        e.setShapeHash(QueryShapeRedactor.shapeHash(e.getNormalizedShape()));
        e.setAttribution(attribution);
        e.setAuditConfidence(AuditConfidence.HIGH);
        e.setObservationSource(ObservationSource.POSTGRES_JSONLOG);
        return e;
    }

    @Test
    void filtersCombineAcrossDimensions() {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        store.record(event(DatabaseEngineType.POSTGRES, "shop", "shop_user", "1.1.1.1", "read", QueryAttribution.AUTHORITATIVE));
        store.record(event(DatabaseEngineType.MYSQL, "shop", "shop_user", "2.2.2.2", "write", QueryAttribution.AUTHORITATIVE));
        store.record(event(DatabaseEngineType.POSTGRES, "blog", "blog_user", "1.1.1.1", "read", QueryAttribution.INFERRED));

        assertThat(store.countFiltered(QueryAuditFilter.empty())).isEqualTo(3);
        assertThat(store.countFiltered(new QueryAuditFilter("POSTGRES", null, null, null, null, null, null, null, null, null, null))).isEqualTo(2);
        assertThat(store.countFiltered(new QueryAuditFilter(null, "shop", null, null, null, null, null, null, null, null, null))).isEqualTo(2);
        assertThat(store.countFiltered(new QueryAuditFilter(null, null, null, "1.1.1.1", null, null, null, null, null, null, null))).isEqualTo(2);
        assertThat(store.countFiltered(new QueryAuditFilter(null, null, null, null, null, null, "INFERRED", null, null, null, null))).isEqualTo(1);
    }

    @Test
    void newestFirstPagination() {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            QueryAuditEvent e = event(DatabaseEngineType.MONGO, "d", "u", "9.9.9.9", "read", QueryAttribution.AUTHORITATIVE);
            e.setObservedAt(base.plusSeconds(i));
            store.record(e);
        }
        var page = store.findFiltered(QueryAuditFilter.empty(), 0, 2);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getObservedAt()).isAfter(page.get(1).getObservedAt());
    }

    @Test
    void boundedStoreEvictsOldestAndCountsDrops() {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        for (int i = 0; i < InMemoryQueryAuditStore.MAX_RETAINED + 10; i++) {
            store.record(event(DatabaseEngineType.POSTGRES, "d", "u", "1.1.1.1", "read", QueryAttribution.AUTHORITATIVE));
        }
        assertThat(store.countFiltered(QueryAuditFilter.empty())).isEqualTo(InMemoryQueryAuditStore.MAX_RETAINED);
        assertThat(store.droppedCount()).isEqualTo(10);
    }

    @Test
    void nullRecordCountsAsDrop() {
        InMemoryQueryAuditStore store = new InMemoryQueryAuditStore();
        store.record(null);
        assertThat(store.droppedCount()).isEqualTo(1);
    }
}
