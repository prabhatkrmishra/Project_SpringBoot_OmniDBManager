package com.pkmprojects.mongodbserver.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PostgresConnectionFactoryTest {
    private static PostgresConnectionEndpointFactory factory() {
        return new PostgresConnectionEndpointFactory("pg.example.com", 27431, "require", null);
    }

    @Test
    void directUsesDirectPort() {
        var ep = factory().direct("customer_db", "u", "p");
        assertThat(ep.host()).isEqualTo("pg.example.com:27431");
        assertThat(ep.mode().name()).isEqualTo("DIRECT");
    }

    @Test
    void noStringReplacementBetweenModes() {
        var conns = factory().both("customer_db", "u", "p", true);
        assertThat(conns.direct().host()).isNotEqualTo(conns.pooled().host());
        assertThat(conns.pooled().poolMode().name()).isEqualTo("TRANSACTION");
    }

    @Test
    void hostnameSelectsModeNeverIdentity() {
        // SNI-identity contract (S-06 audit): same database + same role +
        // same password on either hostname; only host/port/mode differ.
        // Authorization still comes from PostgreSQL per (role, db), never
        // from the hostname, username shape, or packet heuristics.
        var conns = factory().both("customer_db", "tenant_role", "s3cret", true);
        assertThat(conns.direct().database()).isEqualTo(conns.pooled().database());
        assertThat(conns.direct().username()).isEqualTo(conns.pooled().username());
        assertThat(conns.direct().password()).isEqualTo(conns.pooled().password());
        assertThat(conns.direct().mode().name()).isEqualTo("DIRECT");
        assertThat(conns.pooled().mode().name()).isEqualTo("POOLED");
        assertThat(conns.direct().host()).isNotEqualTo(conns.pooled().host());
    }

    @Test
    void builderProducesUriAndJdbc() {
        var b = new PostgresConnectionStringBuilder();
        var conns = factory().both("customer_db", "u", "p", true);
        assertThat(b.toUri(conns.direct())).startsWith("postgresql://u:p@pg.example.com:27431/customer_db");
        assertThat(b.toJdbc(conns.direct())).startsWith("jdbc:postgresql://");
    }

    @Test
    void proxyModeUsesSinglePortDualHostname() {
        var f = factory();
        f.setProxyProperties(new com.pkmprojects.mongodbserver.config.DatabaseProxyProperties(
                true, 15432, "db.example.com", "pool.example.com"));
        var conns = f.both("customer_db", "u", "p", true);
        assertThat(conns.direct().host()).isEqualTo("db.example.com:15432");
        assertThat(conns.pooled().host()).isEqualTo("pool.example.com:15432");
        assertThat(conns.direct().username()).isEqualTo(conns.pooled().username());
    }

    @Test
    void roleNamesAreUniqueAndSafe() {
        var g = new PostgresRoleNameGenerator();
        String a = g.generate("myapp");
        String b = g.generate("myapp");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("omni_[a-z0-9_]+_[a-z0-9]+");
    }
}
