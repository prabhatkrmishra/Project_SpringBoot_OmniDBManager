package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostgresDatabaseEngineTest {

    @Mock
    private PostgresDatabaseRepository postgresDatabaseRepository;
    @Mock
    private Environment environment;

    private PostgresDatabaseEngine engine(String uri, String issuedHost, String sslmode) {
        return new PostgresDatabaseEngine(postgresDatabaseRepository, environment, uri, issuedHost, sslmode);
    }

    private PostgresDatabaseEngine directEngine(String uri, String issuedHost, int issuedPort, String sslmode) {
        return new PostgresDatabaseEngine(postgresDatabaseRepository, environment, uri, issuedHost, issuedPort, sslmode);
    }

    private PostgresDatabaseEngine pooledEngine(String uri, String issuedHost, int directPort, int pooledPort, String sslmode) {
        PgbouncerProperties props = new PgbouncerProperties(6432, pooledPort, "transaction", 1000, 25, 2, 3, 10, "admin", "stats", "authsecret");
        return new PostgresDatabaseEngine(postgresDatabaseRepository, environment, uri, issuedHost, directPort, sslmode, props);
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
        PostgresDatabaseEngine e = directEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "postgres.example.com", 5432, "require");
        String cs = e.buildConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).isEqualTo("postgresql://myuser:mypass@postgres.example.com:5432/mydb?sslmode=require&application_name=omnidb");
    }

    @Test
    void buildConnectionStringWithCustomDirectPort() {
        PostgresDatabaseEngine e = directEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, "require");
        String cs = e.buildConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).isEqualTo("postgresql://myuser:mypass@pg.example.com:27431/mydb?sslmode=require&application_name=omnidb");
    }

    @Test
    void buildConnectionStringWithTlsVerifyFull() {
        PostgresDatabaseEngine e = directEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "postgres.example.com", 5432, "verify-full");
        String cs = e.buildConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).contains("sslmode=verify-full");
        assertThat(cs).contains("application_name=omnidb");
    }

    @Test
    void buildConnectionStringStripsLegacyHostPort() {
        // Legacy POSTGRES_ISSUED_HOST=host:port is stripped with a warning;
        // the port comes from POSTGRES_ISSUED_PORT instead.
        PostgresDatabaseEngine e = directEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com:9999", 27431, "require");
        String cs = e.buildConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).isEqualTo("postgresql://myuser:mypass@pg.example.com:27431/mydb?sslmode=require&application_name=omnidb");
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

    @Test
    void buildPooledConnectionStringUsesPublicPooledPort() {
        PostgresDatabaseEngine e = pooledEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, 27432, "require");
        String cs = e.buildPooledConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).isEqualTo("postgresql://myuser:mypass@pg.example.com:27432/mydb?sslmode=require&application_name=omnidb");
    }

    @Test
    void buildPooledConnectionStringLocalDev() {
        PostgresDatabaseEngine e = pooledEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "", 5432, 6432, "require");
        String cs = e.buildPooledConnectionString("myuser", "mypass", "mydb");
        assertThat(cs).isEqualTo("postgresql://myuser:mypass@127.0.0.1:6432/mydb?sslmode=require&application_name=omnidb");
    }

    @Test
    void resolveDirectAndPooledHostsSplit() {
        PostgresDatabaseEngine e = pooledEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, 27432, "require");
        assertThat(e.resolveDirectHost()).isEqualTo("pg.example.com:27431");
        assertThat(e.resolvePooledHost()).isEqualTo("pg.example.com:27432");
    }

    @Test
    void resolvePooledHostStripsLegacyPort() {
        PostgresDatabaseEngine e = pooledEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com:5432", 27431, 27432, "require");
        assertThat(e.resolvePooledHost()).isEqualTo("pg.example.com:27432");
    }

    @Test
    void constructorRejectsInvalidDirectPort() {
        assertThatThrownBy(() -> directEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 0, "require"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.postgres.issued-port");
        assertThatThrownBy(() -> directEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 70000, "require"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.postgres.issued-port");
    }

    @Test
    void constructorRejectsInvalidPooledPort() {
        PgbouncerProperties bad = new PgbouncerProperties(6432, 0, "transaction", 1000, 25, 2, 3, 10, "admin", "stats", "authsecret");
        assertThatThrownBy(() -> new PostgresDatabaseEngine(postgresDatabaseRepository, environment,
                "jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, "require", bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.pgbouncer.issued-port");
    }

    @Test
    void installPooledAuthDelegatesToRepository() {
        PostgresDatabaseEngine e = pooledEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, 27432, "require");
        e.installPooledAuth("myapp");
        verify(postgresDatabaseRepository).ensureAuthRole("pgbouncer_auth", "authsecret");
        verify(postgresDatabaseRepository).installAuthLookup("myapp", "pgbouncer_auth");
    }

    @Test
    void proxyModeIssuesSinglePortDualHostnameStrings() {
        PostgresDatabaseEngine e = directEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, "require");
        e.setProxyProperties(new com.pkmprojects.mongodbserver.config.DatabaseProxyProperties(
                true, 15432, "db.example.com", "pool.example.com"));
        assertThat(e.buildConnectionString("myuser", "mypass", "mydb"))
                .isEqualTo("postgresql://myuser:mypass@db.example.com:15432/mydb?sslmode=require&application_name=omnidb");
        assertThat(e.buildPooledConnectionString("myuser", "mypass", "mydb"))
                .isEqualTo("postgresql://myuser:mypass@pool.example.com:15432/mydb?sslmode=require&application_name=omnidb");
        var conns = e.connectionEndpoints("mydb", "myuser", "mypass", true);
        assertThat(conns.direct().host()).isEqualTo("db.example.com:15432");
        assertThat(conns.pooled().host()).isEqualTo("pool.example.com:15432");
        assertThat(conns.direct().port()).isEqualTo(15432);
        // Same identity both paths — hostname selects mode only.
        assertThat(conns.direct().username()).isEqualTo(conns.pooled().username());
        assertThat(conns.direct().database()).isEqualTo(conns.pooled().database());
    }

    @Test
    void pooledOnlyPublicServesPooledViaProxyAndKeepsDirectInternal() {
        PostgresDatabaseEngine e = directEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, "require");
        e.setProxyProperties(new com.pkmprojects.mongodbserver.config.DatabaseProxyProperties(
                true, 14291, "", "db.missionhelmai.com"));
        assertThat(e.buildPooledConnectionString("myuser", "mypass", "mydb"))
                .isEqualTo("postgresql://myuser:mypass@db.missionhelmai.com:14291/mydb?sslmode=require&application_name=omnidb");
        // direct has no public route: internal address, never the public hostname
        assertThat(e.buildConnectionString("myuser", "mypass", "mydb")).doesNotContain("db.missionhelmai.com");
        var conns = e.connectionEndpoints("mydb", "myuser", "mypass", true);
        assertThat(conns.pooled().host()).isEqualTo("db.missionhelmai.com:14291");
        assertThat(conns.direct().host()).doesNotContain("db.missionhelmai.com");
        assertThat(conns.direct().username()).isEqualTo(conns.pooled().username());
        assertThat(conns.direct().database()).isEqualTo(conns.pooled().database());
    }

    @Test
    void proxyDisabledKeepsLegacyTwoPortStrings() {
        PostgresDatabaseEngine e = pooledEngine("jdbc:postgresql://127.0.0.1:9813/postgres", "pg.example.com", 27431, 27432, "require");
        e.setProxyProperties(new com.pkmprojects.mongodbserver.config.DatabaseProxyProperties(
                false, 15432, "", ""));
        assertThat(e.buildConnectionString("myuser", "mypass", "mydb")).contains("pg.example.com:27431");
        assertThat(e.buildPooledConnectionString("myuser", "mypass", "mydb")).contains("pg.example.com:27432");
    }

    @Test
    void installPooledAuthWithoutPoolerConfigThrows() {
        PostgresDatabaseEngine e = engine("jdbc:postgresql://127.0.0.1:9813/postgres", "", "require");
        assertThatThrownBy(() -> e.installPooledAuth("myapp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pooling is not configured");
    }
}
