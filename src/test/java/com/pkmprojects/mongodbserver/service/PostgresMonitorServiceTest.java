package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.PostgresMonitorSnapshot;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresMonitorServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Mock
    private PostgresDatabaseRepository postgresRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private PostgresMonitorService service;

    @BeforeEach
    void setUp() {
        service = new PostgresMonitorService(postgresRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void getSnapshotWhenUnreachable() {
        doThrow(new RuntimeException("down")).when(postgresRepository).ping();

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.reachable()).isFalse();
        assertThat(snap.measuredAt()).isEqualTo(NOW);
    }

    @Test
    void getSnapshotWhenReachable() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of(
                "connectionCount", 5, "version", "18.6", "uptimeSeconds", 3600L));
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 1024L, "other", 2048L, "postgres", 512L));
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'active'", Integer.class)).thenReturn(2);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'idle'", Integer.class)).thenReturn(3);
        when(jdbcTemplate.queryForObject("SELECT COALESCE(SUM(xact_commit),0) FROM pg_stat_database", Long.class)).thenReturn(100L);
        when(jdbcTemplate.queryForObject("SELECT COALESCE(SUM(xact_rollback),0) FROM pg_stat_database", Long.class)).thenReturn(5L);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.reachable()).isTrue();
        assertThat(snap.version()).isEqualTo("18.6");
        assertThat(snap.uptimeSeconds()).isEqualTo(3600L);
        assertThat(snap.connectionCount()).isEqualTo(5);
        assertThat(snap.databaseCount()).isEqualTo(2); // postgres excluded
        assertThat(snap.totalStorageBytes()).isEqualTo(3072L);
        assertThat(snap.activeConnections()).isEqualTo(2);
        assertThat(snap.idleConnections()).isEqualTo(3);
        assertThat(snap.transactionsCommitted()).isEqualTo(100L);
        assertThat(snap.transactionsRolledBack()).isEqualTo(5L);
    }

    @Test
    void getSnapshotFallsBackToGetVersion() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of("connectionCount", 1));
        when(postgresRepository.getVersion()).thenReturn("18.6");
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of());
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.version()).isEqualTo("18.6");
    }

    @Test
    void serializeProducesJson() {
        PostgresMonitorSnapshot snap = new PostgresMonitorSnapshot(true, NOW, "18.6", 3600L, 5, 2, 3072L, 2, 3, 100L, 5L);
        String json = service.serialize(snap);
        assertThat(json).contains("\"reachable\":true");
        assertThat(json).contains("\"version\":\"18.6\"");
        assertThat(json).contains("\"databaseCount\":2");
        assertThat(json).contains("\"totalStorageBytes\":3072");
    }

    @Test
    void serializeHandlesNulls() {
        PostgresMonitorSnapshot snap = new PostgresMonitorSnapshot(false, NOW, null, null, null, 0, null, null, null, null, null);
        String json = service.serialize(snap);
        assertThat(json).contains("\"reachable\":false");
        assertThat(json).contains("\"version\":null");
        assertThat(json).contains("\"uptimeSeconds\":null");
    }

    @Test
    void getSnapshotWhenMonitorDataThrowsStillReturnsReachable() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenThrow(new RuntimeException("monitor down"));
        when(postgresRepository.getVersion()).thenReturn("18.6");
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of());
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.reachable()).isTrue();
        assertThat(snap.version()).isEqualTo("18.6");
    }

    @Test
    void getSnapshotWhenVersionFallbackAlsoFailsVersionIsNull() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of());
        when(postgresRepository.getVersion()).thenThrow(new RuntimeException("no version"));
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of());
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.reachable()).isTrue();
        assertThat(snap.version()).isNull();
    }

    @Test
    void getSnapshotWhenDatabaseSizesThrowsDatabaseCountIsZero() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of("version", "18.6"));
        when(postgresRepository.getDatabaseSizes()).thenThrow(new RuntimeException("sizes down"));
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.reachable()).isTrue();
        assertThat(snap.databaseCount()).isZero();
        assertThat(snap.totalStorageBytes()).isNull();
    }

    @Test
    void getSnapshotWhenJdbcThrowsActiveIdleAreNull() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of("version", "18.6"));
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 1024L));
        when(postgresRepository.getJdbcTemplate()).thenThrow(new RuntimeException("jdbc down"));

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.reachable()).isTrue();
        assertThat(snap.activeConnections()).isNull();
        assertThat(snap.idleConnections()).isNull();
        assertThat(snap.transactionsCommitted()).isNull();
    }

    @Test
    void getSnapshotWhenActiveQueryThrowsBothActivityCountsAreNullButXactStillCollected() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of("version", "18.6"));
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 1024L));
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'active'", Integer.class))
                .thenThrow(new RuntimeException("active down"));
        when(jdbcTemplate.queryForObject("SELECT COALESCE(SUM(xact_commit),0) FROM pg_stat_database", Long.class)).thenReturn(10L);
        when(jdbcTemplate.queryForObject("SELECT COALESCE(SUM(xact_rollback),0) FROM pg_stat_database", Long.class)).thenReturn(1L);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        // active/idle share one try block — active failure skips idle
        assertThat(snap.activeConnections()).isNull();
        assertThat(snap.idleConnections()).isNull();
        // xact stats are in a separate try block, still collected
        assertThat(snap.transactionsCommitted()).isEqualTo(10L);
        assertThat(snap.transactionsRolledBack()).isEqualTo(1L);
    }

    @Test
    void getSnapshotExcludesSystemDatabasesFromCount() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of("version", "18.6"));
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of(
                "postgres", 512L, "template0", 100L, "template1", 100L, "myapp", 1024L));
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.databaseCount()).isEqualTo(1);
        assertThat(snap.totalStorageBytes()).isEqualTo(1024L);
    }

    @Test
    void getSnapshotWithEmptyDatabaseSizes() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of("version", "18.6"));
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of());
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.databaseCount()).isZero();
        assertThat(snap.totalStorageBytes()).isZero();
    }

    @Test
    void getSnapshotHandlesNullQueryResults() {
        doNothing().when(postgresRepository).ping();
        when(postgresRepository.getPostgresMonitorData()).thenReturn(Map.of("version", "18.6"));
        when(postgresRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 1024L));
        when(postgresRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'active'", Integer.class)).thenReturn(null);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'idle'", Integer.class)).thenReturn(null);
        when(jdbcTemplate.queryForObject("SELECT COALESCE(SUM(xact_commit),0) FROM pg_stat_database", Long.class)).thenReturn(null);
        when(jdbcTemplate.queryForObject("SELECT COALESCE(SUM(xact_rollback),0) FROM pg_stat_database", Long.class)).thenReturn(null);

        PostgresMonitorSnapshot snap = service.getSnapshot();

        assertThat(snap.activeConnections()).isZero();
        assertThat(snap.idleConnections()).isZero();
        assertThat(snap.transactionsCommitted()).isZero();
        assertThat(snap.transactionsRolledBack()).isZero();
    }

    @Test
    void serializeIncludesAllFields() {
        PostgresMonitorSnapshot snap = new PostgresMonitorSnapshot(true, NOW, "18.6", 3600L, 5, 2, 3072L, 2, 3, 100L, 5L);
        String json = service.serialize(snap);
        assertThat(json).contains("\"reachable\":true");
        assertThat(json).contains("\"measuredAt\":\"2026-08-18T10:00:00Z\"");
        assertThat(json).contains("\"version\":\"18.6\"");
        assertThat(json).contains("\"uptimeSeconds\":3600");
        assertThat(json).contains("\"connectionCount\":5");
        assertThat(json).contains("\"activeConnections\":2");
        assertThat(json).contains("\"idleConnections\":3");
        assertThat(json).contains("\"transactionsCommitted\":100");
        assertThat(json).contains("\"transactionsRolledBack\":5");
    }

    @Test
    void getSnapshotMeasuredAtIsFixedClockTime() {
        doThrow(new RuntimeException("down")).when(postgresRepository).ping();
        PostgresMonitorSnapshot snap = service.getSnapshot();
        assertThat(snap.measuredAt()).isEqualTo(NOW);
    }
}
