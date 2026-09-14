package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.PgbouncerHcProperties;
import com.pkmprojects.mongodbserver.model.ConnectionEndpoint;
import com.pkmprojects.mongodbserver.model.ConnectionMode;
import com.pkmprojects.mongodbserver.model.DatabaseConnections;
import com.pkmprojects.mongodbserver.model.PoolMode;
import com.pkmprojects.mongodbserver.model.SslMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** HC validation is genuinely live-tested, never inferred from standard. */
@ExtendWith(MockitoExtension.class)
class ConnectionValidationHcTest {
    @Mock PostgresDatabaseEngine engine;

    static class ScriptedHc extends ConnectionValidationService {
        final List<String> attempted = new ArrayList<>();
        boolean hcPublicOk;
        boolean loopbackOk;
        ConnectionEndpoint loopbackEndpoint;

        ScriptedHc(PostgresDatabaseEngine engine) {
            super(engine, new PostgresConnectionStringBuilder());
        }

        @Override
        boolean runHcPublic(ConnectionEndpoint ep) {
            attempted.add("hc-public");
            return hcPublicOk;
        }

        @Override
        boolean runLoopback(ConnectionEndpoint endpoint) {
            attempted.add("pooled-loopback");
            loopbackEndpoint = endpoint;
            return loopbackOk;
        }

        @Override
        boolean run(String mode, ConnectionEndpoint endpoint) {
            attempted.add(mode);
            return false;
        }
    }

    private ScriptedHc scripted(boolean hcPublicOk, boolean loopbackOk) {
        var direct = new ConnectionEndpoint("db.example.com:15432", 15432, "myapp", "u", "p",
                SslMode.REQUIRE, ConnectionMode.DIRECT, null);
        var pooled = new ConnectionEndpoint("db.example.com:15432", 15432, "myapp", "u", "p",
                SslMode.REQUIRE, ConnectionMode.POOLED, PoolMode.TRANSACTION);
        lenient().when(engine.connectionEndpoints(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new DatabaseConnections(direct, pooled));
        lenient().when(engine.isProxyMode()).thenReturn(true);
        ScriptedHc s = new ScriptedHc(engine);
        s.setHcProperties(new PgbouncerHcProperties(6433, 1000, 15, 5, 3, 25, "a", "s", "auth"));
        s.hcPublicOk = hcPublicOk;
        s.loopbackOk = loopbackOk;
        return s;
    }

    @Test
    void hcPublicProvesHc() {
        var s = scripted(true, false);
        var r = s.validateHcDetailed("myapp", "u", "p");
        assertThat(r.healthy()).isTrue();
        assertThat(r.path()).isEqualTo(ConnectionValidationService.ValidationPath.PUBLIC);
    }

    @Test
    void hcLoopbackFallbackUsesHcPort() {
        var s = scripted(false, true);
        var r = s.validateHcDetailed("myapp", "u", "p");
        assertThat(r.healthy()).isTrue();
        assertThat(r.path()).isEqualTo(ConnectionValidationService.ValidationPath.LOOPBACK);
        assertThat(s.loopbackEndpoint.port()).isEqualTo(6433);
    }

    @Test
    void hcUnconfiguredIsUnhealthy() {
        var direct = new ConnectionEndpoint("db.example.com:15432", 15432, "myapp", "u", "p",
                SslMode.REQUIRE, ConnectionMode.DIRECT, null);
        var pooled = new ConnectionEndpoint("db.example.com:15432", 15432, "myapp", "u", "p",
                SslMode.REQUIRE, ConnectionMode.POOLED, PoolMode.TRANSACTION);
        lenient().when(engine.connectionEndpoints(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new DatabaseConnections(direct, pooled));
        var s = new ScriptedHc(engine);
        // No HC properties wired.
        var r = s.validateHcDetailed("myapp", "u", "p");
        assertThat(r.healthy()).isFalse();
    }
}
