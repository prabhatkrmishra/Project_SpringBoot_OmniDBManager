package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code provisioned_databases} gauge per engine (MONGO / POSTGRES / MYSQL).
 * Value is read live from {@link ManagedDatabaseRepository} so it reflects
 * provision/delete without restart. Visible at {@code /actuator/metrics/provisioned.databases}
 * and via Prometheus if enabled.
 */
@Component
public class ProvisionedDatabaseMetrics {

    public ProvisionedDatabaseMetrics(MeterRegistry registry, ManagedDatabaseRepository repository) {
        for (DatabaseEngineType engine : DatabaseEngineType.values()) {
            Gauge.builder("provisioned.databases", repository,
                            repo -> {
                                try {
                                    return (double) repo.countByEngineType(engine);
                                } catch (Exception e) {
                                    return Double.NaN;
                                }
                            })
                    .tag("engine", engine.name())
                    .description("Number of provisioned databases for engine " + engine.name())
                    .register(registry);
        }
        Gauge.builder("provisioned.databases.total", repository,
                        repo -> {
                            try {
                                return (double) repo.count();
                            } catch (Exception e) {
                                return Double.NaN;
                            }
                        })
                .description("Total number of provisioned databases")
                .register(registry);
    }
}
