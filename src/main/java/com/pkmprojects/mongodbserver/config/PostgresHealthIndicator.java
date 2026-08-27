package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for PostgreSQL. Exposed at {@code /actuator/health}
 * as component {@code postgres}. When {@code app.postgres.enabled=false} the
 * bean is absent and health shows only MongoDB.
 */
@Component
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresHealthIndicator implements HealthIndicator {

    private final PostgresDatabaseRepository repository;

    public PostgresHealthIndicator(PostgresDatabaseRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        try {
            repository.ping();
            String version = null;
            try {
                version = repository.getVersion();
            } catch (Exception ignored) {
            }
            Health.Builder builder = Health.up().withDetail("reachable", true);
            if (version != null) {
                builder.withDetail("version", version);
            }
            return builder.build();
        } catch (Exception e) {
            return Health.down(e).withDetail("reachable", false).build();
        }
    }
}
