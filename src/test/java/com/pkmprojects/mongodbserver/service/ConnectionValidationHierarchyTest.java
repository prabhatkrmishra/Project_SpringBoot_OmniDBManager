package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.ConnectionEndpoint;
import com.pkmprojects.mongodbserver.model.ConnectionMode;
import com.pkmprojects.mongodbserver.model.DatabaseConnections;
import com.pkmprojects.mongodbserver.model.PoolMode;
import com.pkmprojects.mongodbserver.model.SslMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The loopback tier must never be reported as equivalent to the public path:
 * only PUBLIC proves the complete app-facing contract.
 */
@ExtendWith(MockitoExtension.class)
class ConnectionValidationHierarchyTest {

    @Mock private PostgresDatabaseEngine engine;

    private static ConnectionEndpoint ep(String host, int port, ConnectionMode mode, PoolMode pool) {
        return new ConnectionEndpoint(host, port, "myapp", "u", "p", SslMode.REQUIRE, mode, pool);
    }

    /** Stubbed tiers: record which routes were attempted, script results. */
    static class Scripted extends ConnectionValidationService {
        final List<String> attempted = new ArrayList<>();
        boolean publicOk;
        boolean loopbackOk;

        Scripted(PostgresDatabaseEngine engine) {
            super(engine, new PostgresConnectionStringBuilder());
        }

        ConnectionEndpoint loopbackEndpoint;

        @Override
        protected boolean run(String mode, ConnectionEndpoint endpoint) {
            attempted.add(mode);
            if (mode.equals("pooled-loopback")) loopbackEndpoint = endpoint;
            return mode.equals("pooled") ? publicOk : loopbackOk;
        }
    }

    private Scripted scripted(boolean publicOk, boolean loopbackOk, boolean pooledEnabled) {
        var direct = ep("db.example.com:15432", 15432, ConnectionMode.DIRECT, null);
        var pooled = pooledEnabled ? ep("pool.example.com:15432", 15432, ConnectionMode.POOLED, PoolMode.TRANSACTION) : null;
        when(engine.connectionEndpoints(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new DatabaseConnections(direct, pooled));
        Scripted s = new Scripted(engine);
        s.publicOk = publicOk;
        s.loopbackOk = loopbackOk;
        return s;
    }

    @Test
    void publicSuccessShortCircuitsWithPublicPath() {
        Scripted s = scripted(true, false, true);
        var r = s.validatePooledDetailed("myapp", "u", "p");
        assertThat(r.healthy()).isTrue();
        assertThat(r.path()).isEqualTo(ConnectionValidationService.ValidationPath.PUBLIC);
        assertThat(s.attempted).containsExactly("pooled");
    }

    @Test
    void loopbackFallbackIsReportedAsLoopbackNotPublic() {
        Scripted s = scripted(false, true, true);
        var r = s.validatePooledDetailed("myapp", "u", "p");
        assertThat(r.healthy()).isTrue();
        assertThat(r.path()).isEqualTo(ConnectionValidationService.ValidationPath.LOOPBACK);
        assertThat(s.attempted).containsExactly("pooled", "pooled-loopback");
    }

    @Test
    void bothTiersFailingIsUnhealthy() {
        Scripted s = scripted(false, false, true);
        var r = s.validatePooledDetailed("myapp", "u", "p");
        assertThat(r.healthy()).isFalse();
        assertThat(r.path()).isEqualTo(ConnectionValidationService.ValidationPath.NONE);
    }

    @Test
    void loopbackSslModeFollowsPoolerReality() {
        // proxy off (plaintext pooler): loopback must be DISABLE or require
        // fails closed against 127.0.0.1:6432 and provisioning can never pass
        Scripted off = scripted(false, true, true);
        off.validatePooledDetailed("myapp", "u", "p");
        assertThat(off.loopbackEndpoint.sslMode()).isEqualTo(SslMode.DISABLE);
        // proxy on (pooler terminates client TLS): loopback keeps require
        when(engine.isProxyMode()).thenReturn(true);
        Scripted on = scripted(false, true, true);
        on.validatePooledDetailed("myapp", "u", "p");
        assertThat(on.loopbackEndpoint.sslMode()).isEqualTo(SslMode.REQUIRE);
    }

    @Test
    void directOnlyDatabaseHasNoPooledPath() {
        Scripted s = scripted(false, false, false);
        var r = s.validatePooledDetailed("myapp", "u", "p");
        assertThat(r.healthy()).isFalse();
        assertThat(r.path()).isEqualTo(ConnectionValidationService.ValidationPath.NONE);
        assertThat(s.attempted).isEmpty();
    }
}
