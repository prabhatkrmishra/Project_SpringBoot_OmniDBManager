package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DatabaseNameValidatorPostgresTest {

    private final DatabaseNameValidator validator = new DatabaseNameValidator();

    @Test
    void acceptsValidPostgresDatabaseNames() {
        assertDoesNotThrow(() -> validator.validatePostgresDatabaseName("myapp"));
        assertDoesNotThrow(() -> validator.validatePostgresDatabaseName("my_app_1"));
        assertDoesNotThrow(() -> validator.validatePostgresDatabaseName("_private"));
        assertDoesNotThrow(() -> validator.validatePostgresDatabaseName("a"));
        assertDoesNotThrow(() -> validator.validatePostgresDatabaseName("a1_b2"));
    }

    @Test
    void rejectsBlankPostgresDatabaseName() {
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("  "))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName(null))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName(""))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsUppercasePostgresDatabaseName() {
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("MyApp"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("MYAPP"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsPostgresDatabaseNameStartingWithDigit() {
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("1myapp"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("9_test"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsPostgresDatabaseNameWithHyphenOrDot() {
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("my-app"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("my.app"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("my app"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsTooLongPostgresDatabaseName() {
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("a".repeat(64)))
                .isInstanceOf(NameNotAllowedException.class);
        assertDoesNotThrow(() -> validator.validatePostgresDatabaseName("a".repeat(63)));
    }

    @Test
    void rejectsPostgresSystemDatabases() {
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("postgres"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("template0"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("template1"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsUppercaseSystemNameDueToPatternNotSystemCheck() {
        // Uppercase fails pattern before system check — still rejected for pattern, not system
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("POSTGRES"))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("lowercase letters");
        assertThatThrownBy(() -> validator.validatePostgresDatabaseName("Template0"))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("lowercase letters");
    }

    @Test
    void validateDatabaseNameDispatchesByEngine() {
        assertDoesNotThrow(() -> validator.validateDatabaseName("myapp", DatabaseEngineType.POSTGRES));
        assertThatThrownBy(() -> validator.validateDatabaseName("MyApp", DatabaseEngineType.POSTGRES))
                .isInstanceOf(NameNotAllowedException.class);
        assertDoesNotThrow(() -> validator.validateDatabaseName("MyApp", DatabaseEngineType.MONGO));
    }

    @Test
    void acceptsValidPostgresUserNames() {
        assertDoesNotThrow(() -> validator.validatePostgresUserName("app_user"));
        assertDoesNotThrow(() -> validator.validatePostgresUserName("myapp_1"));
        assertDoesNotThrow(() -> validator.validatePostgresUserName("_admin"));
    }

    @Test
    void rejectsInvalidPostgresUserNames() {
        assertThatThrownBy(() -> validator.validatePostgresUserName("AppUser"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresUserName("my-app"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresUserName("1user"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validatePostgresUserName("a".repeat(64)))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void validateUserNameDispatchesByEngine() {
        assertDoesNotThrow(() -> validator.validateUserName("app_user", DatabaseEngineType.POSTGRES));
        assertThatThrownBy(() -> validator.validateUserName("AppUser", DatabaseEngineType.POSTGRES))
                .isInstanceOf(NameNotAllowedException.class);
        // mongo allows dots and hyphens
        assertDoesNotThrow(() -> validator.validateUserName("app.user-1", DatabaseEngineType.MONGO));
    }

    @Test
    void acceptsValidTableNames() {
        assertDoesNotThrow(() -> validator.validateTableName("users"));
        assertDoesNotThrow(() -> validator.validateTableName("order_items"));
        assertDoesNotThrow(() -> validator.validateTableName("_tmp"));
    }

    @Test
    void rejectsInvalidTableNames() {
        assertThatThrownBy(() -> validator.validateTableName("Users"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateTableName("my-table"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateTableName("1table"))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> validator.validateTableName("a".repeat(64)))
                .isInstanceOf(NameNotAllowedException.class);
    }
}
