package com.pkmprojects.mongodbserver.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MysqlDatabaseRepositoryUnitTest {

    // ── quoteIdentifier ───────────────────────────────────────────────

    @Test
    void quoteIdentifierWrapsInBackticks() {
        assertThat(MysqlDatabaseRepository.quoteIdentifier("myapp")).isEqualTo("`myapp`");
    }

    @Test
    void quoteIdentifierEscapesBackticks() {
        assertThat(MysqlDatabaseRepository.quoteIdentifier("my`app")).isEqualTo("`my``app`");
    }

    // ── quoteUser ─────────────────────────────────────────────────────

    @Test
    void quoteUserWrapsInMysqlUserFormat() {
        assertThat(MysqlDatabaseRepository.quoteUser("bob")).isEqualTo("'bob'@'%'");
    }

    @Test
    void quoteUserEscapesSingleQuotes() {
        assertThat(MysqlDatabaseRepository.quoteUser("o'neil")).isEqualTo("'o''neil'@'%'");
    }

    // ── SQL injection defense ────────────────────────────────────────

    @Test
    void createUserEscapesSingleQuoteInPassword() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        repo.createUser("mydb", "bob", "it'sasecret");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.contains("'it''sasecret'")));
    }

    @Test
    void createUserRejectsPasswordWithSemicolon() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        assertThatThrownBy(() -> repo.createUser("mydb", "bob", "pass;word"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed SQL metacharacters");
    }

    @Test
    void updateUserPasswordEscapesSingleQuote() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        repo.updateUserPassword("mydb", "bob", "new'pass");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.contains("'new''pass'")));
    }
}
