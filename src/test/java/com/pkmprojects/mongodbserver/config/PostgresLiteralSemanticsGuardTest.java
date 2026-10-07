package com.pkmprojects.mongodbserver.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL password quoting is only correct while standard_conforming_strings
 * is on. The guard exists so that is asserted rather than assumed.
 */
@ExtendWith(MockitoExtension.class)
class PostgresLiteralSemanticsGuardTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void startsWhenTheSettingIsOn() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("on");
        var guard = new PostgresLiteralSemanticsGuard(jdbcTemplate);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void toleratesWhitespaceAndCaseAroundTheValue() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(" ON ");
        var guard = new PostgresLiteralSemanticsGuard(jdbcTemplate);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void refusesWhenTheSettingIsOff() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("off");
        var guard = new PostgresLiteralSemanticsGuard(jdbcTemplate);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("standard_conforming_strings")
                .hasMessageContaining("ALTER SYSTEM");
    }

    @Test
    void startsWhenTheProbeItselfErrors() {
        // An unreachable database is not evidence that the setting is off, and a
        // control plane must stay bootable while the database it manages is down.
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("connection refused"));
        var guard = new PostgresLiteralSemanticsGuard(jdbcTemplate);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void startsOnANullValue() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(null);
        var guard = new PostgresLiteralSemanticsGuard(jdbcTemplate);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void aWrongValueStillStopsStartup() {
        // The distinction this guard turns on: told-off is fatal, cannot-tell is not.
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("off");
        var guard = new PostgresLiteralSemanticsGuard(jdbcTemplate);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("standard_conforming_strings");
    }

    @Test
    void queriesTheSettingDirectlyRatherThanGuessing() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("on");
        new PostgresLiteralSemanticsGuard(jdbcTemplate).run(null);

        verify(jdbcTemplate).queryForObject(
                "SELECT current_setting('standard_conforming_strings')", String.class);
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void isNotRegisteredWhenPostgresIsDisabled() {
        // Registration is conditional; assert the annotation is present so the
        // class cannot silently become unconditional and demand a database that
        // this deployment never enabled.
        var conditional = PostgresLiteralSemanticsGuard.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        org.assertj.core.api.Assertions.assertThat(conditional).isNotNull();
        org.assertj.core.api.Assertions.assertThat(conditional.name())
                .containsExactly("app.postgres.enabled");
        org.assertj.core.api.Assertions.assertThat(conditional.havingValue()).isEqualTo("true");
        verifyNoInteractions(jdbcTemplate);
    }
}
