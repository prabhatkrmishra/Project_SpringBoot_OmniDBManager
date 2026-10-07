package com.pkmprojects.mongodbserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the PostgreSQL setting the password path silently depends on.
 *
 * <p>{@code PostgresDatabaseRepository.escapePassword} doubles single quotes.
 * That is correct only while {@code standard_conforming_strings} is on, which
 * makes a backslash an ordinary character rather than an escape. Turn it off and
 * the same quoting lets a crafted password terminate the literal early, so the
 * rest of the password is parsed as SQL instead of stored.
 *
 * <p>Nothing in this project sets that GUC, and the value lives in the server's
 * own configuration, so the code was relying on a default nobody had asserted.
 * One {@code ALTER SYSTEM} or an extra {@code -c} flag on the server would void
 * it without any signal here. This asserts the assumption instead.
 *
 * <p>Only a definite "off" stops startup. Being unable to ask is treated
 * differently from being told the answer is wrong: an unreachable database says
 * nothing about the setting, and refusing to boot whenever PostgreSQL is briefly
 * down would make this control plane unavailable for the one condition it exists
 * to diagnose. So an inconclusive probe is logged loudly and startup continues --
 * provisioning will fail on its own if the database is genuinely gone.
 *
 * <p>Residual gap, stated plainly: if PostgreSQL is unreachable at startup and
 * comes back with the setting off, the misconfiguration is not caught here. The
 * operator sees a warning rather than a refusal. Tightening that would mean
 * re-checking on the provisioning path, which is not worth a query per password
 * operation for a setting that should never be off in the first place.
 */
@Component
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresLiteralSemanticsGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PostgresLiteralSemanticsGuard.class);

    private final JdbcTemplate jdbcTemplate;

    public PostgresLiteralSemanticsGuard(
            @org.springframework.beans.factory.annotation.Qualifier("postgresJdbcTemplate")
            JdbcTemplate postgresJdbcTemplate) {
        this.jdbcTemplate = postgresJdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String value;
        try {
            value = jdbcTemplate.queryForObject(
                    "SELECT current_setting('standard_conforming_strings')", String.class);
        } catch (Exception e) {
            log.warn("Could not read standard_conforming_strings from PostgreSQL ({}: {}). "
                            + "Tenant-password quoting depends on it being on — if provisioning fails "
                            + "with a syntax error near a password, check this setting first.",
                    e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        if (value == null || value.isBlank()) {
            log.warn("PostgreSQL returned no value for standard_conforming_strings; cannot confirm that "
                    + "tenant-password quoting is safe. Expected 'on'.");
            return;
        }
        if (!"on".equalsIgnoreCase(value.trim())) {
            throw new IllegalStateException(
                    "standard_conforming_strings is '" + value.trim() + "' on PostgreSQL, expected 'on'. "
                            + "With it off, a backslash escapes inside the password literal and a crafted "
                            + "password can terminate it early. Refusing to start rather than provision "
                            + "databases whose credentials we cannot quote safely. Fix with: "
                            + "ALTER SYSTEM SET standard_conforming_strings = on; then restart PostgreSQL.");
        }
        log.info("standard_conforming_strings=on confirmed; password literal quoting is safe.");
    }
}
