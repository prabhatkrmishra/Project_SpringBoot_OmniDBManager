package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProvisionedDatabaseMetricsTest {

    @Mock
    private ManagedDatabaseStore repository;

    @Test
    void registersGaugesPerEngineAndTotal() {
        when(repository.countByEngineType(DatabaseEngineType.MONGO)).thenReturn(3L);
        when(repository.countByEngineType(DatabaseEngineType.POSTGRES)).thenReturn(2L);
        when(repository.count()).thenReturn(5L);

        MeterRegistry registry = new SimpleMeterRegistry();
        new ProvisionedDatabaseMetrics(registry, repository);

        Gauge mongoGauge = registry.find("provisioned.databases").tag("engine", "MONGO").gauge();
        Gauge pgGauge = registry.find("provisioned.databases").tag("engine", "POSTGRES").gauge();
        Gauge totalGauge = registry.find("provisioned.databases.total").gauge();

        assertThat(mongoGauge).isNotNull();
        assertThat(pgGauge).isNotNull();
        assertThat(totalGauge).isNotNull();
        assertThat(mongoGauge.value()).isEqualTo(3.0);
        assertThat(pgGauge.value()).isEqualTo(2.0);
        assertThat(totalGauge.value()).isEqualTo(5.0);
    }

    @Test
    void gaugeReturnsNaNWhenRepositoryThrows() {
        when(repository.countByEngineType(DatabaseEngineType.MONGO)).thenThrow(new RuntimeException("db down"));
        when(repository.count()).thenThrow(new RuntimeException("db down"));

        MeterRegistry registry = new SimpleMeterRegistry();
        new ProvisionedDatabaseMetrics(registry, repository);

        Gauge mongoGauge = registry.find("provisioned.databases").tag("engine", "MONGO").gauge();
        Gauge totalGauge = registry.find("provisioned.databases.total").gauge();

        assertThat(mongoGauge.value()).isNaN();
        assertThat(totalGauge.value()).isNaN();
    }

    @Test
    void gaugeReflectsLiveCountAfterMutation() {
        when(repository.countByEngineType(DatabaseEngineType.MONGO)).thenReturn(1L).thenReturn(4L);

        MeterRegistry registry = new SimpleMeterRegistry();
        new ProvisionedDatabaseMetrics(registry, repository);

        Gauge mongoGauge = registry.find("provisioned.databases").tag("engine", "MONGO").gauge();
        assertThat(mongoGauge.value()).isEqualTo(1.0);
        // second call reflects updated mock
        assertThat(mongoGauge.value()).isEqualTo(4.0);
    }

    @Test
    void gaugesHaveDescriptions() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new ProvisionedDatabaseMetrics(registry, repository);

        Gauge mongoGauge = registry.find("provisioned.databases").tag("engine", "MONGO").gauge();
        assertThat(mongoGauge.getId().getDescription()).contains("MONGO");
        Gauge totalGauge = registry.find("provisioned.databases.total").gauge();
        assertThat(totalGauge.getId().getDescription()).contains("Total");
    }
}
