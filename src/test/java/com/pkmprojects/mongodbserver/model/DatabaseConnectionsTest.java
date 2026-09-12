package com.pkmprojects.mongodbserver.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class DatabaseConnectionsTest {
    private static ConnectionEndpoint direct() {
        return new ConnectionEndpoint("db.example.com:15432", 15432, "customer_db", "u", "p",
                SslMode.REQUIRE, ConnectionMode.DIRECT, null);
    }

    private static ConnectionEndpoint pooled() {
        return new ConnectionEndpoint("pool.example.com:15432", 15432, "customer_db", "u", "p",
                SslMode.REQUIRE, ConnectionMode.POOLED, PoolMode.TRANSACTION);
    }

    @Test
    void directOnlyWhenNotPooled() {
        var conns = DatabaseConnections.fromLegacy(false, direct(), pooled());
        assertThat(conns.isPooledEnabled()).isFalse();
        assertThat(conns.pooled()).isNull();
    }

    @Test
    void bothWhenPooled() {
        var conns = DatabaseConnections.fromLegacy(true, direct(), pooled());
        assertThat(conns.isPooledEnabled()).isTrue();
        assertThat(conns.pooled().mode()).isEqualTo(ConnectionMode.POOLED);
        assertThat(conns.pooled().poolMode()).isEqualTo(PoolMode.TRANSACTION);
    }

    @Test
    void rejectsWrongModes() {
        assertThatThrownBy(() -> new DatabaseConnections(pooled(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseConnections(direct(), direct())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutSecretNeverCarriesPassword() {
        PublicConnectionEndpoint pub = pooled().withoutSecret();
        assertThat(pub.host()).contains("pool.example.com");
        assertThat(pub.toString()).doesNotContain("\"p\"");
    }

    @Test
    void sslModeParsing() {
        assertThat(SslMode.parse("verify-full", SslMode.REQUIRE)).isEqualTo(SslMode.VERIFY_FULL);
        assertThat(SslMode.parse("", SslMode.REQUIRE)).isEqualTo(SslMode.REQUIRE);
        assertThat(SslMode.REQUIRE.wireValue()).isEqualTo("require");
    }
}
