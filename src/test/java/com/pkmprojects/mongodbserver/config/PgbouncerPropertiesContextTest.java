package com.pkmprojects.mongodbserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression guard: {@link PgbouncerProperties} is a record bound via
 * constructor binding. Adding a second constructor overload silently
 * disables that (container falls back to no-args instantiation) and the
 * entire application context fails with "No default constructor found".
 * This test boots the real context so that can never regress unnoticed —
 * the Docker-gated tests only cover it when a daemon is present.
 */
@SpringBootTest(properties = {"app.postgres.enabled=true"})
class PgbouncerPropertiesContextTest {
    @Autowired
    private PgbouncerProperties pgbouncerProperties;

    @Test
    void contextLoadsAndBindsPoolerProperties() {
        assertThat(pgbouncerProperties.port()).isEqualTo(6432);
        assertThat(pgbouncerProperties.poolMode()).isEqualTo("transaction");
        assertThat(pgbouncerProperties.defaultPoolSize()).isEqualTo(5);
    }
}
