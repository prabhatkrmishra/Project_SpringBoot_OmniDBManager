package com.pkmprojects.mongodbserver.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PostgresDatabaseRepositoryUnitTest {

    private PostgresDatabaseRepository repo(String uri) {
        return new PostgresDatabaseRepository(mock(JdbcTemplate.class), uri, "root", "root");
    }

    // ── quoteIdentifier ───────────────────────────────────────────────

    @Test
    void quoteIdentifierWrapsInDoubleQuotes() {
        assertThat(PostgresDatabaseRepository.quoteIdentifier("myapp")).isEqualTo("\"myapp\"");
    }

    @Test
    void quoteIdentifierEscapesDoubleQuotes() {
        assertThat(PostgresDatabaseRepository.quoteIdentifier("my\"app")).isEqualTo("\"my\"\"app\"");
        assertThat(PostgresDatabaseRepository.quoteIdentifier("a\"b\"c")).isEqualTo("\"a\"\"b\"\"c\"");
    }

    @Test
    void quoteIdentifierHandlesUnderscoreAndDigits() {
        assertThat(PostgresDatabaseRepository.quoteIdentifier("my_app_1")).isEqualTo("\"my_app_1\"");
    }

    // ── urlFor ────────────────────────────────────────────────────────

    @Test
    void urlForReplacesDatabaseName() {
        assertThat(repo("jdbc:postgresql://127.0.0.1:9813/postgres").urlFor("myapp"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:9813/myapp");
    }

    @Test
    void urlForPreservesQueryParams() {
        assertThat(repo("jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=require").urlFor("myapp"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:9813/myapp?sslmode=require");
    }

    @Test
    void urlForPreservesMultipleQueryParams() {
        assertThat(repo("jdbc:postgresql://host:5432/postgres?sslmode=require&ApplicationName=omnidb").urlFor("mydb"))
                .isEqualTo("jdbc:postgresql://host:5432/mydb?sslmode=require&ApplicationName=omnidb");
    }

    @Test
    void urlForWithNoSlashInsertsDatabase() {
        assertThat(repo("jdbc:postgresql://127.0.0.1:9813").urlFor("myapp"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:9813/myapp");
    }

    @Test
    void urlForWithNoSlashButQueryInsertsBeforeQuery() {
        assertThat(repo("jdbc:postgresql://127.0.0.1:9813?sslmode=require").urlFor("myapp"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:9813/myapp?sslmode=require");
    }

    @Test
    void urlForWithNoSchemeThrows() {
        assertThatThrownBy(() -> repo("not-a-uri").urlFor("myapp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JDBC URI");
    }

    @Test
    void urlForWithDifferentHostAndPort() {
        assertThat(repo("jdbc:postgresql://db.example.com:5432/postgres").urlFor("myapp"))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/myapp");
    }

    // ── SQL injection defense ────────────────────────────────────────

    @Test
    void createUserEscapesSingleQuoteInPassword() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new PostgresDatabaseRepository(jdbc, "jdbc:postgresql://127.0.0.1:9813/postgres", "root", "root");
        repo.createUser("mydb", "bob", "it'sasecret");
        org.mockito.Mockito.verify(jdbc).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.contains("'it''sasecret'")));
    }

    @Test
    void createUserCreatesRoleWhenNotPresent() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new PostgresDatabaseRepository(jdbc, "jdbc:postgresql://127.0.0.1:9813/postgres", "root", "root");
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(0);
        repo.createUser("mydb", "bob", "secret123");
        org.mockito.Mockito.verify(jdbc).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.startsWith("CREATE ROLE \"bob\"")));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.startsWith("ALTER ROLE")));
    }

    @Test
    void createUserAltersExistingRoleInsteadOfFailing() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new PostgresDatabaseRepository(jdbc, "jdbc:postgresql://127.0.0.1:9813/postgres", "root", "root");
        // Role already exists (orphaned from a prior provisioning whose database was dropped)
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(1);
        repo.createUser("mydb", "bob", "secret123");
        org.mockito.Mockito.verify(jdbc).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.startsWith("ALTER ROLE \"bob\"") && sql.contains("'secret123'")));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.startsWith("CREATE ROLE")));
    }

    @Test
    void createUserRejectsPasswordWithSemicolon() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new PostgresDatabaseRepository(jdbc, "jdbc:postgresql://127.0.0.1:9813/postgres", "root", "root");
        assertThatThrownBy(() -> repo.createUser("mydb", "bob", "pass;word"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed SQL metacharacters");
    }

    @Test
    void updateUserPasswordEscapesSingleQuote() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new PostgresDatabaseRepository(jdbc, "jdbc:postgresql://127.0.0.1:9813/postgres", "root", "root");
        repo.updateUserPassword("mydb", "bob", "new'pass");
        org.mockito.Mockito.verify(jdbc).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.contains("'new''pass'")));
    }

    @Test
    void updateUserPasswordAltersExistingRole() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new PostgresDatabaseRepository(jdbc, "jdbc:postgresql://127.0.0.1:9813/postgres", "root", "root");
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(1);
        repo.updateUserPassword("mydb", "bob", "secret123");
        org.mockito.Mockito.verify(jdbc).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.startsWith("ALTER ROLE \"bob\"") && sql.contains("'secret123'")));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.startsWith("CREATE ROLE")));
    }

    @Test
    void updateUserPasswordRecreatesRoleWhenMissing() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new PostgresDatabaseRepository(jdbc, "jdbc:postgresql://127.0.0.1:9813/postgres", "root", "root");
        // Role dropped out-of-band (e.g. manual cleanup) — reset should recreate it
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(0);
        repo.updateUserPassword("mydb", "bob", "secret123");
        org.mockito.Mockito.verify(jdbc).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.startsWith("CREATE ROLE \"bob\"") && sql.contains("LOGIN") && sql.contains("'secret123'")));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute((String) org.mockito.Mockito.argThat((String sql) -> sql.startsWith("ALTER ROLE")));
    }
}
