package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.service.PgbouncerMonitorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for PgBouncer — facet of Postgres engine health.
 * Mirrors {@code PostgresHealthIndicator}/{@code MysqlHealthIndicator} shape.
 * Only registered when {@code app.pgbouncer.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.pgbouncer.enabled", havingValue = "true")
public class PgbouncerHealthIndicator implements HealthIndicator {

    private final PgbouncerMonitorService monitorService;

    public PgbouncerHealthIndicator(PgbouncerMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Override
    public Health health() {
        try {
            monitorService.ping();
            return Health.up().withDetail("reachable", true).withDetail("pool_mode", "transaction").build();
        } catch (Exception e) {
            return Health.down(e).withDetail("reachable", false).build();
        }
    }
}
