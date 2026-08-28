package com.pkmprojects.mongodbserver.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
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
    void urlForWithNoSchemeReturnsAsIs() {
        assertThat(repo("not-a-uri").urlFor("myapp")).isEqualTo("not-a-uri");
    }

    @Test
    void urlForWithDifferentHostAndPort() {
        assertThat(repo("jdbc:postgresql://db.example.com:5432/postgres").urlFor("myapp"))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/myapp");
    }
}
