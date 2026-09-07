package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PostgresDatabaseEngineTest {

    @Mock
    private PostgresDatabaseRepository postgresDatabaseRepository;
    @Mock
    private Environment environment;

    private PostgresDatabaseEngine engine(String uri, String issuedHost, String sslmode) {
        return new PostgresDatabaseEngine(postgresDatabaseRepository, environment, uri, issuedHost, sslmode);
    }

    @Test
    void typeIsPostgres() {
        assertThat(engine("jdbc:postgresql://127.0.0.1:9813/postgres", "", "require").type())
                .isEqualTo(DatabaseEngineType.POSTGRES);
    }

    @Test
    void buildConnectionStringWithoutTls() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres", "", "require");
        String cs = e.buildConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).isEqualTo("postgresql://myuser:mypass@127.0.0.1:9813/mydb?sslmode=require&application_name=omnidb");
    }

    @Test
    void buildConnectionStringWithTlsRequire() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres", "postgres.example.com:5432", "require");
        String cs = e.buildConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).isEqualTo("postgresql://myuser:mypass@postgres.example.com:5432/mydb?sslmode=require&application_name=omnidb");
    }

    @Test
    void buildConnectionStringWithTlsVerifyFull() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres", "postgres.example.com:5432", "verify-full");
        String cs = e.buildConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).contains("sslmode=verify-full");
        assertThat(cs).contains("application_name=omnidb");
    }

    @Test
    void buildConnectionStringEncodesSpecialChars() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres", "", "require");
        String cs = e.buildConnectionString("user@name", "p@ss#word/x?y", "mydb");
        assertThat(cs).isEqualTo("postgresql://user%40name:p%40ss%23word%2Fx%3Fy@127.0.0.1:9813/mydb?sslmode=require&application_name=omnidb");
    }

    @Test
    void buildConnectionStringEncodesPercentAndColon() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres", "", "require");
        String cs = e.buildConnectionString("myuser", "s3cret%#@:", "mydb");
        assertThat(cs).contains("s3cret%25%23%40%3A");
    }

    @Test
    void resolveHostUsesPublicHostWhenSet() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres", "postgres.example.com:5432", "require");
        assertThat(e.resolveHost()).isEqualTo("postgres.example.com:5432");
    }

    @Test
    void resolveHostDerivesFromUri() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://db.example.com:5432/postgres", "", "require");
        assertThat(e.resolveHost()).isEqualTo("db.example.com:5432");
    }

    @Test
    void resolveHostDerivesFromUriWithQueryParams() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=require", "", "require");
        assertThat(e.resolveHost()).isEqualTo("127.0.0.1:9813");
    }

    @Test
    void resolveHostFallbackWhenNoScheme() {
        PostgresDatabaseEngine e = engine("not-a-uri", "", "require");
        assertThat(e.resolveHost()).isEqualTo("127.0.0.1:9813");
    }

    @Test
    void resolveHostFallbackWhenBlank() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres", "   ", "require");
        assertThat(e.resolveHost()).isEqualTo("127.0.0.1:9813");
    }

    @Test
    void uriEncodeLeavesUnreservedChars() {
        assertThat(PostgresDatabaseEngine.uriEncode("abcABC123-._~")).isEqualTo("abcABC123-._~");
    }

    @Test
    void uriEncodeEncodesReservedChars() {
        assertThat(PostgresDatabaseEngine.uriEncode("a b/c?d&e=f")).isEqualTo("a%20b%2Fc%3Fd%26e%3Df");
        assertThat(PostgresDatabaseEngine.uriEncode("p@ss:word")).isEqualTo("p%40ss%3Aword");
        assertThat(PostgresDatabaseEngine.uriEncode("100%")).isEqualTo("100%25");
    }

    @Test
    void uriEncodeHandlesUtf8() {
        // é = 0xC3 0xA9 in UTF-8
        assertThat(PostgresDatabaseEngine.uriEncode("café")).isEqualTo("caf%C3%A9");
    }
}
