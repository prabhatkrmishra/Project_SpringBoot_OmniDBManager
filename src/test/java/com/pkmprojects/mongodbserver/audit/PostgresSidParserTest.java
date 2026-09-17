package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.ingest.PostgresJsonlogParser;
import org.junit.jupiter.api.Test;

/** Bridge-SID extraction from application_name=omnidb:{sid}. */
class PostgresSidParserTest {

    @Test
    void extractsWellFormedSid() {
        assertThat(PostgresJsonlogParser.extractBridgeSid("omnidb:abc123def456")).isEqualTo("abc123def456");
        assertThat(PostgresJsonlogParser.extractBridgeSid("omnidb:aaaabbbbcccc")).isEqualTo("aaaabbbbcccc");
    }

    @Test
    void rejectsBareAndMalformed() {
        assertThat(PostgresJsonlogParser.extractBridgeSid("omnidb")).isNull();
        assertThat(PostgresJsonlogParser.extractBridgeSid("psql")).isNull();
        assertThat(PostgresJsonlogParser.extractBridgeSid("")).isNull();
        assertThat(PostgresJsonlogParser.extractBridgeSid(null)).isNull();
        assertThat(PostgresJsonlogParser.extractBridgeSid("omnidb:SHORT")).isNull();
        assertThat(PostgresJsonlogParser.extractBridgeSid("omnidb:has space")).isNull();
        assertThat(PostgresJsonlogParser.extractBridgeSid("omnidb:ABC123DEF456")).isNull();
        assertThat(PostgresJsonlogParser.extractBridgeSid("other:abc123def456")).isNull();
    }

    @Test
    void tenantMarkerAcceptsBareAndSid() {
        assertThat(PostgresJsonlogParser.isTenantAppName("omnidb")).isTrue();
        assertThat(PostgresJsonlogParser.isTenantAppName("omnidb:abc123def456")).isTrue();
        assertThat(PostgresJsonlogParser.isTenantAppName("psql")).isFalse();
        assertThat(PostgresJsonlogParser.isTenantAppName("")).isFalse();
        assertThat(PostgresJsonlogParser.isTenantAppName(null)).isFalse();
    }

    @Test
    void sidCarriesNoSecretsByConstruction() {
        // Format guarantee: 12 hex chars — cannot encode IP/user/password.
        String sid = PostgresJsonlogParser.extractBridgeSid("omnidb:9f8e7d6c5b4a");
        assertThat(sid).matches("[a-z0-9]{12}");
    }
}
