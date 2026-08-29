package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code provisioned_databases} gauge per engine (MONGO / POSTGRES / MYSQL).
 * Value is read live from {@link ManagedDatabaseStore} so it reflects
 * provision/delete without restart. Visible at {@code /actuator/metrics/provisioned.databases}
 * and via Prometheus if enabled.
 */
@Component
public class ProvisionedDatabaseMetrics {

    public ProvisionedDatabaseMetrics(MeterRegistry registry, ManagedDatabaseStore store) {
        for (DatabaseEngineType engine : DatabaseEngineType.values()) {
            Gauge.builder("provisioned.databases", store,
                            s -> {
                                try {
                                    return (double) s.countByEngineType(engine);
                                } catch (Exception e) {
                                    return Double.NaN;
                                }
                            })
                    .tag("engine", engine.name())
                    .description("Number of provisioned databases for engine " + engine.name())
                    .register(registry);
        }
        Gauge.builder("provisioned.databases.total", store,
                        s -> {
                            try {
                                return (double) s.count();
                            } catch (Exception e) {
                                return Double.NaN;
                            }
                        })
                .description("Total number of provisioned databases")
                .register(registry);
    }
}
