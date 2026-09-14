package com.pkmprojects.mongodbserver.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Provision-time tenant-login validation for MySQL/MongoDB.
 * URL-shape derivation is unit-tested without live engines; live login
 * proof runs in disposable-DB validation.
 */
@ExtendWith(MockitoExtension.class)
class TenantLoginValidationServiceTest {

    @Mock
    private Environment env;

    private TenantLoginValidationService service(String mysqlUri, boolean mysqlOn, boolean mongoOn) {
        return new TenantLoginValidationService(env, mysqlUri, mysqlOn, mongoOn);
    }

    @Test
    void mysqlTenantJdbcSwapsCatalogKeepsHostAndParams() {
        TenantLoginValidationService svc = service(
                "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                true, false);

        assertThat(svc.tenantMysqlJdbc("shop"))
                .isEqualTo("jdbc:mysql://127.0.0.1:9816/shop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    }

    @Test
    void mysqlTenantJdbcNeverEmbedsCredentials() {
        TenantLoginValidationService svc = service(
                "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false", true, false);

        String jdbc = svc.tenantMysqlJdbc("shop");
        assertThat(jdbc).doesNotContain("shop_user");
        assertThat(jdbc).doesNotContain("secret");
        assertThat(jdbc).startsWith("jdbc:mysql://127.0.0.1:9816/shop");
    }

    @Test
    void mysqlValidationFailsClosedWhenDisabled() {
        TenantLoginValidationService svc = service("jdbc:mysql://127.0.0.1:9816/mysql", false, false);

        assertThat(svc.validateMysql("shop", "u", "p")).isFalse();
    }

    @Test
    void mysqlValidationFailsClosedOnUnreachableEngine() {
        // No MySQL on 127.0.0.1:9 — must return false, never throw.
        TenantLoginValidationService svc = service("jdbc:mysql://127.0.0.1:9/mysql", true, false);

        assertThat(svc.validateMysql("shop", "u", "p")).isFalse();
    }

    @Test
    void mongoTenantUriSwapsCredentialsAndPinsAuthSource() {
        when(env.getProperty("spring.mongodb.uri", ""))
                .thenReturn("mongodb://root:root@127.0.0.1:9812/?authSource=admin&maxPoolSize=10");
        TenantLoginValidationService svc = service("jdbc:mysql://127.0.0.1:9816/mysql", false, true);

        String uri = svc.tenantMongoUri("shop", "shop_user", "s3cret");

        assertThat(uri).startsWith("mongodb://shop_user:s3cret@127.0.0.1:9812/");
        assertThat(uri).contains("authSource=shop");
        assertThat(uri).doesNotContain("root:root");
        assertThat(uri).contains("maxPoolSize=10");
    }

    @Test
    void mongoTenantUriFailsClosedOnUnparseableRoot() {
        when(env.getProperty("spring.mongodb.uri", "")).thenReturn("not-a-uri");
        TenantLoginValidationService svc = service("jdbc:mysql://127.0.0.1:9816/mysql", false, true);

        assertThat(svc.tenantMongoUri("shop", "u", "p")).isNull();
        assertThat(svc.validateMongo("shop", "u", "p")).isFalse();
    }

    @Test
    void mongoValidationFailsClosedWhenDisabled() {
        TenantLoginValidationService svc = service("jdbc:mysql://127.0.0.1:9816/mysql", false, false);

        assertThat(svc.validateMongo("shop", "u", "p")).isFalse();
    }

    @Test
    void mongoValidationFailsClosedOnUnreachableEngine() {
        when(env.getProperty("spring.mongodb.uri", ""))
                .thenReturn("mongodb://u:p@127.0.0.1:9/?authSource=admin&serverSelectionTimeoutMS=1000");
        TenantLoginValidationService svc = service("jdbc:mysql://127.0.0.1:9816/mysql", false, true);

        assertThat(svc.validateMongo("shop", "u", "p")).isFalse();
    }
}
