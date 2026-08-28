package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for MySQL. Exposed at {@code /actuator/health}
 * as component {@code mysql}. When {@code app.mysql.enabled=false} the
 * bean is absent and health shows only MongoDB/Postgres.
 */
@Component
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlHealthIndicator implements HealthIndicator {

    private final MysqlDatabaseRepository repository;

    public MysqlHealthIndicator(MysqlDatabaseRepository repository) {
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
