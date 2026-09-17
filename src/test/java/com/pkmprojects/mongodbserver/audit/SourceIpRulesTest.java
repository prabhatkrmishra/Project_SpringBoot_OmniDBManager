package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.ingest.SourceIpRules;
import org.junit.jupiter.api.Test;

/** Common source-IP sanity rules: literals only, no hostnames, no loopback, no DNS. */
class SourceIpRulesTest {

    @Test
    void ipv4AndIpv6LiteralsAccepted() {
        assertThat(SourceIpRules.sanitizeTenantIp("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(SourceIpRules.sanitizeTenantIp("10.0.0.8")).isEqualTo("10.0.0.8");
        assertThat(SourceIpRules.sanitizeTenantIp("192.168.1.20")).isEqualTo("192.168.1.20");
        assertThat(SourceIpRules.sanitizeTenantIp("2001:db8::1")).isEqualTo("2001:db8::1");
        assertThat(SourceIpRules.sanitizeTenantIp("::ffff:203.0.113.7")).isEqualTo("::ffff:203.0.113.7");
    }

    @Test
    void privateClientAddressesAllowed() {
        // Private deployments: client private IPs are genuine tenant IPs.
        assertThat(SourceIpRules.sanitizeTenantIp("10.1.2.3")).isEqualTo("10.1.2.3");
        assertThat(SourceIpRules.sanitizeTenantIp("172.18.0.4")).isEqualTo("172.18.0.4");
        assertThat(SourceIpRules.sanitizeTenantIp("192.168.0.10")).isEqualTo("192.168.0.10");
    }

    @Test
    void loopbackRejected() {
        assertThat(SourceIpRules.sanitizeTenantIp("127.0.0.1")).isNull();
        assertThat(SourceIpRules.sanitizeTenantIp("::1")).isNull();
        assertThat(SourceIpRules.sanitizeTenantIp("::ffff:127.0.0.1")).isNull();
        assertThat(SourceIpRules.sanitizeTenantIp("localhost")).isNull();
    }

    @Test
    void hostnamesRejectedWithoutDns() {
        assertThat(SourceIpRules.sanitizeTenantIp("db.internal")).isNull();
        assertThat(SourceIpRules.sanitizeTenantIp("postgres")).isNull();
        assertThat(SourceIpRules.sanitizeTenantIp("app-host")).isNull();
        assertThat(SourceIpRules.sanitizeTenantIp("")).isNull();
        assertThat(SourceIpRules.sanitizeTenantIp(null)).isNull();
    }

    @Test
    void portSuffixStrippedForIpLiterals() {
        assertThat(SourceIpRules.sanitizeTenantIp("203.0.113.7:54322")).isEqualTo("203.0.113.7");
        assertThat(SourceIpRules.sanitizeTenantIp("[2001:db8::1]:54322")).isEqualTo("2001:db8::1");
        assertThat(SourceIpRules.sanitizeTenantIp("[203.0.113.7]")).isEqualTo("203.0.113.7");
    }
}
