package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.PostgresDatabaseStats;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import com.pkmprojects.mongodbserver.error.ProvisioningException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresStatisticsServiceTest {

    @Mock
    private PostgresDatabaseRepository postgresRepository;

    private PostgresStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new PostgresStatisticsService(postgresRepository, new DatabaseNameValidator());
    }

    @Test
    void getDatabaseStatsAggregatesTables() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users", "orders"));
        when(postgresRepository.countRows("myapp", "users")).thenReturn(10L);
        when(postgresRepository.countRows("myapp", "orders")).thenReturn(5L);
        when(postgresRepository.getTableSizeQualified("myapp", "users")).thenReturn(8192L);
        when(postgresRepository.getTableSizeQualified("myapp", "orders")).thenReturn(4096L);
        when(postgresRepository.getTableStats("myapp", "users")).thenReturn(Map.of("n_live_tup", 10L, "n_dead_tup", 1L));
        when(postgresRepository.getTableStats("myapp", "orders")).thenReturn(Map.of("n_live_tup", 5L, "n_dead_tup", 0L));

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.dbName()).isEqualTo("myapp");
        assertThat(stats.tableCount()).isEqualTo(2);
        assertThat(stats.totalRows()).isEqualTo(15L);
        assertThat(stats.totalSizeBytes()).isEqualTo(12288L);
        assertThat(stats.tables()).hasSize(2);
    }

    @Test
    void getDatabaseStatsEmptyDatabase() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of());

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tableCount()).isZero();
        assertThat(stats.totalRows()).isZero();
        assertThat(stats.totalSizeBytes()).isZero();
        assertThat(stats.tables()).isEmpty();
    }

    @Test
    void getDatabaseStatsSingleTableNoParallel() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.countRows("myapp", "users")).thenReturn(3L);
        when(postgresRepository.getTableSizeQualified("myapp", "users")).thenReturn(1024L);
        when(postgresRepository.getTableStats("myapp", "users")).thenReturn(Map.of("n_live_tup", 3L, "n_dead_tup", 0L));

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tableCount()).isEqualTo(1);
        assertThat(stats.totalRows()).isEqualTo(3L);
    }

    @Test
    void getDatabaseStatsDegradesOnCountFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.countRows("myapp", "users")).thenThrow(new RuntimeException("boom"));
        when(postgresRepository.getTableSizeQualified("myapp", "users")).thenReturn(0L);
        when(postgresRepository.getTableStats("myapp", "users")).thenReturn(Map.of());

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tables().get(0).rowCount()).isZero();
    }

    @Test
    void getDatabaseStatsOnMissingDatabaseThrows() {
        when(postgresRepository.databaseExists("nope")).thenReturn(false);
        assertThatThrownBy(() -> service.getDatabaseStats("nope"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void getDatabaseStatsRejectsInvalidName() {
        assertThatThrownBy(() -> service.getDatabaseStats("MyApp"))
                .isInstanceOf(NameNotAllowedException.class);
        verify(postgresRepository, never()).listTables(anyString());
    }

    @Test
    void getDatabaseStatsHandlesVacuumFields() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.countRows("myapp", "users")).thenReturn(1L);
        when(postgresRepository.getTableSizeQualified("myapp", "users")).thenReturn(512L);
        when(postgresRepository.getTableStats("myapp", "users")).thenReturn(Map.of(
                "n_live_tup", 1L, "n_dead_tup", 0L,
                "last_vacuum", "2026-08-18T10:00:00Z",
                "last_analyze", "2026-08-18T11:00:00Z"));

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tables().get(0).lastVacuum()).isEqualTo("2026-08-18T10:00:00Z");
        assertThat(stats.tables().get(0).lastAnalyze()).isEqualTo("2026-08-18T11:00:00Z");
    }

    @Test
    void getDatabaseStatsHandlesAutovacuumFallback() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.countRows("myapp", "users")).thenReturn(1L);
        when(postgresRepository.getTableSizeQualified("myapp", "users")).thenReturn(512L);
        when(postgresRepository.getTableStats("myapp", "users")).thenReturn(Map.of(
                "n_live_tup", 1L, "n_dead_tup", 0L,
                "last_autovacuum", "2026-08-18T09:00:00Z",
                "last_autoanalyze", "2026-08-18T10:00:00Z"));

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tables().get(0).lastVacuum()).isEqualTo("2026-08-18T09:00:00Z");
        assertThat(stats.tables().get(0).lastAnalyze()).isEqualTo("2026-08-18T10:00:00Z");
    }

    @Test
    void getDatabaseStatsWrapsListTablesFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenThrow(new RuntimeException("boom"));
        assertThatThrownBy(() -> service.getDatabaseStats("myapp"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not read statistics");
    }

    @Test
    void getDatabaseStatsWrapsDatabaseExistsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenThrow(new RuntimeException("db down"));
        assertThatThrownBy(() -> service.getDatabaseStats("myapp"))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void getDatabaseStatsDegradesOnSizeFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.countRows("myapp", "users")).thenReturn(5L);
        when(postgresRepository.getTableSizeQualified("myapp", "users")).thenThrow(new RuntimeException("size boom"));
        when(postgresRepository.getTableStats("myapp", "users")).thenReturn(Map.of("n_live_tup", 5L));

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tables().get(0).sizeBytes()).isZero();
        assertThat(stats.totalSizeBytes()).isZero();
    }

    @Test
    void getDatabaseStatsDegradesOnStatFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.countRows("myapp", "users")).thenReturn(5L);
        when(postgresRepository.getTableSizeQualified("myapp", "users")).thenReturn(1024L);
        when(postgresRepository.getTableStats("myapp", "users")).thenThrow(new RuntimeException("stat boom"));

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tables().get(0).rowCount()).isEqualTo(5L);
        assertThat(stats.tables().get(0).sizeBytes()).isEqualTo(1024L);
    }

    @Test
    void getDatabaseStatsParallelWithThreeTables() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("a", "b", "c"));
        for (String t : List.of("a", "b", "c")) {
            when(postgresRepository.countRows("myapp", t)).thenReturn(1L);
            when(postgresRepository.getTableSizeQualified("myapp", t)).thenReturn(100L);
            when(postgresRepository.getTableStats("myapp", t)).thenReturn(Map.of("n_live_tup", 1L));
        }

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tableCount()).isEqualTo(3);
        assertThat(stats.totalRows()).isEqualTo(3L);
        assertThat(stats.totalSizeBytes()).isEqualTo(300L);
        assertThat(stats.tables()).hasSize(3);
    }

    @Test
    void getDatabaseStatsHandlesNullVacuumFields() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.countRows("myapp", "users")).thenReturn(1L);
        when(postgresRepository.getTableSizeQualified("myapp", "users")).thenReturn(512L);
        java.util.Map<String, Object> statMap = new java.util.HashMap<>();
        statMap.put("n_live_tup", 1L);
        statMap.put("n_dead_tup", 0L);
        when(postgresRepository.getTableStats("myapp", "users")).thenReturn(statMap);

        PostgresDatabaseStats stats = service.getDatabaseStats("myapp");

        assertThat(stats.tables().get(0).lastVacuum()).isNull();
        assertThat(stats.tables().get(0).lastAnalyze()).isNull();
    }
}
