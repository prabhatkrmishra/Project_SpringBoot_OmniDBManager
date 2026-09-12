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
    void builderProducesUriAndJdbc() {
        var b = new PostgresConnectionStringBuilder();
        var conns = factory().both("customer_db", "u", "p", true);
        assertThat(b.toUri(conns.direct())).startsWith("postgresql://u:p@pg.example.com:27431/customer_db");
        assertThat(b.toJdbc(conns.direct())).startsWith("jdbc:postgresql://");
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
