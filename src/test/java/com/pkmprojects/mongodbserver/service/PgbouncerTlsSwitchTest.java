package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.DatabaseProxyProperties;
import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * S-06 pooler-TLS switch: with the TLS-passthrough proxy configured, even
 * loopback pooler connections (admin console, stats polling) must use
 * {@code sslmode=require} because PgBouncer itself terminates client TLS
 * ({@code client_tls_sslmode=require}). Otherwise the pooler is plaintext
 * (two-port TLS-termination model) and {@code sslmode=disable} applies.
 */
class PgbouncerTlsSwitchTest {

    private static PgbouncerProperties props() {
        return new PgbouncerProperties(6432, 6432, "transaction", 1000, 5, 2, 3, 10,
                "admin-pass", "stats-pass", "auth-pass");
    }

    private static DatabaseProxyProperties proxyOn() {
        return new DatabaseProxyProperties(true, 15432, "db.example.com", "pool.example.com");
    }

    @Test
    void adminUsesDisableWhenNoProxyConfigured() {
        assertThat(new PgbouncerAdminService(props()).poolerSslMode()).isEqualTo("disable");
    }

    @Test
    void adminUsesRequireWhenProxyConfigured() {
        var svc = new PgbouncerAdminService(props());
        svc.setProxyProperties(proxyOn());
        assertThat(svc.poolerSslMode()).isEqualTo("require");
    }

    @Test
    void adminStaysDisableWhenProxyPresentButUnconfigured() {
        var svc = new PgbouncerAdminService(props());
        // enabled but hostnames missing/differ-check fails -> legacy two-port behavior
        svc.setProxyProperties(new DatabaseProxyProperties(false, 15432, "db.example.com", "pool.example.com"));
        assertThat(svc.poolerSslMode()).isEqualTo("disable");
        svc.setProxyProperties(new DatabaseProxyProperties(true, 15432, "same.example.com", "same.example.com"));
        assertThat(svc.poolerSslMode()).isEqualTo("disable");
    }

    @Test
    void monitorUsesDisableWhenNoProxyConfigured() {
        assertThat(new PgbouncerMonitorService(props()).poolerSslMode()).isEqualTo("disable");
    }

    @Test
    void monitorUsesRequireWhenProxyConfigured() {
        var svc = new PgbouncerMonitorService(props());
        svc.setProxyProperties(proxyOn());
        assertThat(svc.poolerSslMode()).isEqualTo("require");
    }

    @Test
    void monitorStaysDisableWhenProxyPresentButUnconfigured() {
        var svc = new PgbouncerMonitorService(props());
        svc.setProxyProperties(new DatabaseProxyProperties(true, 15432, "", ""));
        assertThat(svc.poolerSslMode()).isEqualTo("disable");
    }
}
