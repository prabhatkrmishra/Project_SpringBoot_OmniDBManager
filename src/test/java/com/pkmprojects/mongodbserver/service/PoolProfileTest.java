package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.DatabaseProxyProperties;
import com.pkmprojects.mongodbserver.config.PgbouncerHcProperties;
import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import com.pkmprojects.mongodbserver.model.ConnectionMode;
import com.pkmprojects.mongodbserver.model.PoolMode;
import com.pkmprojects.mongodbserver.model.PoolProfile;
import com.pkmprojects.mongodbserver.model.SslMode;
import com.pkmprojects.mongodbserver.model.ConnectionEndpoint;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S-14 connection profiles: standard + high_concurrency, transaction pooling
 * only, same host/port/credentials, profile token in options=.
 */
@ExtendWith(MockitoExtension.class)
class PoolProfileTest {

    @Mock
    private PostgresDatabaseRepository repo;
    @Mock
    private Environment env;

    private PostgresDatabaseEngine proxyEngine() {
        PgbouncerProperties props = new PgbouncerProperties(6432, 6432, "transaction",
                1000, 5, 2, 3, 10, "admin", "stats", "authsecret");
        PostgresDatabaseEngine e = new PostgresDatabaseEngine(repo, env,
                "jdbc:postgresql://127.0.0.1:9813/postgres", "db.example.com", 15432, "require", props);
        e.setProxyProperties(new DatabaseProxyProperties(true, 15432, "db.example.com"));
        return e;
    }

    @Test
    void profileIdsAreExact() {
        assertThat(PoolProfile.STANDARD.id()).isEqualTo("standard");
        assertThat(PoolProfile.HIGH_CONCURRENCY.id()).isEqualTo("high_concurrency");
        assertThat(PoolProfile.parse("standard")).isEqualTo(PoolProfile.STANDARD);
        assertThat(PoolProfile.parse("high_concurrency")).isEqualTo(PoolProfile.HIGH_CONCURRENCY);
        assertThatThrownBy(() -> PoolProfile.parse("Standard")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PoolProfile.parse("banana")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PoolProfile.isSupported("standard")).isTrue();
        assertThat(PoolProfile.isSupported("high_concurrency")).isTrue();
        assertThat(PoolProfile.isSupported("session")).isFalse();
    }

    @Test
    void modeOptionForProfile() {
        assertThat(PostgresConnectionStringBuilder.modeOptionForProfile(null))
                .isEqualTo(PostgresConnectionStringBuilder.MODE_OPTION_POOLED);
        assertThat(PostgresConnectionStringBuilder.modeOptionForProfile(PoolProfile.STANDARD))
                .isEqualTo("-c omnidb.mode=pooled -c omnidb.pool_profile=standard");
        assertThat(PostgresConnectionStringBuilder.modeOptionForProfile(PoolProfile.HIGH_CONCURRENCY))
                .isEqualTo("-c omnidb.mode=pooled -c omnidb.pool_profile=high_concurrency");
    }

    @Test
    void bridgedProfileStringsCarryEncodedProfile() {
        var b = new PostgresConnectionStringBuilder();
        var ep = new ConnectionEndpoint("db.example.com:15432", 15432, "mydb", "u", "p",
                SslMode.REQUIRE, ConnectionMode.POOLED, PoolMode.TRANSACTION);
        String stdUri = b.toUriBridged(ep, PoolProfile.STANDARD);
        String hcUri = b.toUriBridged(ep, PoolProfile.HIGH_CONCURRENCY);
        String bareUri = b.toUriBridged(ep);
        // Canonical shapes from the S-14 contract.
        assertThat(hcUri).contains("options=-c%20omnidb.mode%3Dpooled%20-c%20omnidb.pool_profile%3Dhigh_concurrency");
        assertThat(stdUri).contains("options=-c%20omnidb.mode%3Dpooled%20-c%20omnidb.pool_profile%3Dstandard");
        // Bare pooled still works and means standard (backwards compat).
        assertThat(bareUri).contains("options=" + PostgresConnectionStringBuilder.encode(
                PostgresConnectionStringBuilder.MODE_OPTION_POOLED));
        assertThat(bareUri).doesNotContain("pool_profile");
        // Channel binding preserved.
        assertThat(hcUri).contains("channel_binding=disable");
        assertThat(stdUri).contains("channel_binding=disable");
        // JDBC mirrors URI.
        assertThat(b.toJdbcBridged(ep, PoolProfile.HIGH_CONCURRENCY)).contains("channelBinding=disable");
        assertThat(b.toJdbcBridged(ep, PoolProfile.HIGH_CONCURRENCY)).contains("options=");
        // Plain strings unchanged (never carry profile).
        assertThat(b.toUri(ep)).doesNotContain("options=");
        assertThat(b.toUri(ep)).doesNotContain("pool_profile");
        assertThat(b.toJdbc(ep)).doesNotContain("pool_profile");
    }

    @Test
    void engineProfileStringsSameHostPort() {
        PostgresDatabaseEngine e = proxyEngine();
        String bare = e.buildPooledConnectionString("u", "p", "mydb");
        String std = e.buildPooledConnectionString("u", "p", "mydb", PoolProfile.STANDARD);
        String hc = e.buildPooledConnectionString("u", "p", "mydb", PoolProfile.HIGH_CONCURRENCY);
        // Identical host/port — only options= differs.
        assertThat(bare).startsWith("postgresql://u:p@db.example.com:15432/mydb?");
        assertThat(std).startsWith("postgresql://u:p@db.example.com:15432/mydb?");
        assertThat(hc).startsWith("postgresql://u:p@db.example.com:15432/mydb?");
        assertThat(bare).doesNotContain("pool_profile");
        assertThat(std).contains("pool_profile%3Dstandard");
        assertThat(hc).contains("pool_profile%3Dhigh_concurrency");
        // Direct strings never carry profile.
        assertThat(e.buildConnectionString("u", "p", "mydb")).doesNotContain("pool_profile");
    }

    @Test
    void engineEndpointsIdenticalHostPort() {
        PostgresDatabaseEngine e = proxyEngine();
        var conns = e.connectionEndpoints("mydb", "u", "p", true);
        assertThat(conns.direct().host()).isEqualTo("db.example.com:15432");
        assertThat(conns.pooled().host()).isEqualTo("db.example.com:15432");
        assertThat(conns.direct().port()).isEqualTo(15432);
        assertThat(conns.pooled().port()).isEqualTo(15432);
    }

    @Test
    void adminHcDisabledByDefault() {
        PgbouncerProperties props = new PgbouncerProperties(6432, 6432, "transaction",
                1000, 5, 2, 3, 10, "a", "s", "auth");
        var admin = new PgbouncerAdminService(props);
        assertThat(admin.isHcEnabled()).isFalse();
        assertThat(admin.hcPort()).isEqualTo(6433);
    }

    @Test
    void adminHcEnabledWhenWired() {
        PgbouncerProperties props = new PgbouncerProperties(6432, 6432, "transaction",
                1000, 5, 2, 3, 10, "a", "s", "auth");
        var admin = new PgbouncerAdminService(props);
        admin.setHcProperties(new PgbouncerHcProperties(6433, 1000, 15, 5, 3, 25, "a", "s", "auth"));
        assertThat(admin.isHcEnabled()).isTrue();
        assertThat(admin.hcPort()).isEqualTo(6433);
    }

    @Test
    void hcPropertiesDefaults() {
        var hc = new PgbouncerHcProperties(0, 1000, 15, 5, 3, 25, "a", "s", "auth");
        assertThat(hc.port()).isEqualTo(6433);
        assertThat(hc.adminUser()).isEqualTo("pgbouncer_admin");
        assertThat(hc.authUser()).isEqualTo("pgbouncer_auth");
    }
}
// Note: appended HC lifecycle tests live in PooledResumeHcTest to keep this
// file focused on string/profile contracts.
