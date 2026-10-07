package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.service.MongoDatabaseEngine;
import com.pkmprojects.mongodbserver.service.MysqlDatabaseEngine;
import com.pkmprojects.mongodbserver.service.PostgresDatabaseEngine;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Structured connections metadata for MySQL and MongoDB.
 * ADMIN only, mirroring the PostgreSQL API and every other controller here.
 * This previously asserted ADMIN + USER read; no principal can hold USER, since
 * the single {@code roles(...)} call in the codebase assigns ADMIN, so the
 * contract being asserted was one nothing could satisfy.
 */
@WebMvcTest({MysqlConnectionsApiController.class, MongoConnectionsApiController.class,
        PostgresConnectionsApiController.class})
@Import({com.pkmprojects.mongodbserver.config.SecurityConfig.class,
        EngineConnectionsApiTest.SecurityTestConfig.class})
@TestPropertySource(properties = {"app.mysql.enabled=true", "app.mongo.enabled=true",
        "app.postgres.enabled=true"})
class EngineConnectionsApiTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProvisioningService provisioningService;

    @MockitoBean
    private MysqlDatabaseEngine mysqlEngine;

    @MockitoBean
    private MongoDatabaseEngine mongoEngine;

    @MockitoBean
    private PostgresDatabaseEngine postgresEngine;

    private ManagedDatabase mysqlDb() {
        ManagedDatabase md = new ManagedDatabase("shop", DatabaseEngineType.MYSQL,
                "shop_user", List.of("ALL:shop"), NOW, NOW, null);
        return md;
    }

    private ManagedDatabase mongoDb() {
        return new ManagedDatabase("shop", DatabaseEngineType.MONGO,
                "shop_user", List.of("readWrite:shop"), NOW, NOW, null);
    }

    @Test
    void mysqlConnectionsForAdmin() throws Exception {
        when(provisioningService.findManagedDatabase(DatabaseEngineType.MYSQL, "shop"))
                .thenReturn(Optional.of(mysqlDb()));
        when(mysqlEngine.resolveHost()).thenReturn("db.example.com:9816");
        when(mysqlEngine.isTls()).thenReturn(false);

        mockMvc.perform(get("/api/mysql/databases/shop/connections").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("shop"))
                .andExpect(jsonPath("$.direct.enabled").value(true))
                .andExpect(jsonPath("$.direct.host").value("db.example.com:9816"))
                .andExpect(jsonPath("$.direct.mode").value("DIRECT"));
    }

    @Test
    void postgresConnectionsRejectNonAdminReader() throws Exception {
        // The PostgreSQL endpoint previously had no coverage at all, so tightening
        // its @PreAuthorize was unverified. Pin it now.
        when(provisioningService.findManagedDatabase(DatabaseEngineType.POSTGRES, "shop"))
                .thenReturn(Optional.of(postgresDb()));

        mockMvc.perform(get("/api/postgres/databases/shop/connections").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void postgresConnectionsForAdmin() throws Exception {
        when(provisioningService.findManagedDatabase(DatabaseEngineType.POSTGRES, "shop"))
                .thenReturn(Optional.of(postgresDb()));
        when(postgresEngine.connectionEndpoints(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new com.pkmprojects.mongodbserver.model.DatabaseConnections(
                        new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                                "db.example.com", 27431, "shop", "shop_user", "",
                                com.pkmprojects.mongodbserver.model.SslMode.REQUIRE,
                                com.pkmprojects.mongodbserver.model.ConnectionMode.DIRECT,
                                null),
                        null));

        mockMvc.perform(get("/api/postgres/databases/shop/connections").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("shop"));
    }

    private ManagedDatabase postgresDb() {
        ManagedDatabase md = new ManagedDatabase("shop", DatabaseEngineType.POSTGRES,
                "shop_user", List.of("ALL:shop"), NOW, NOW, null);
        md.setPooled(false);
        return md;
    }

    @Test
    void mysqlConnectionsRejectNonAdminReader() throws Exception {
        // Was: USER expected 200. No principal can hold USER -- the only roles(...)
        // call in the codebase assigns ADMIN -- so the annotation advertised a
        // read-only contract nothing could satisfy.
        when(provisioningService.findManagedDatabase(DatabaseEngineType.MYSQL, "shop"))
                .thenReturn(Optional.of(mysqlDb()));
        when(mysqlEngine.resolveHost()).thenReturn("db.example.com:9816");
        when(mysqlEngine.isTls()).thenReturn(true);

        mockMvc.perform(get("/api/mysql/databases/shop/connections").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void mysqlConnectionsAnonymouslyRedirected() throws Exception {
        mockMvc.perform(get("/api/mysql/databases/shop/connections"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void mysqlConnectionsUnknownDatabaseIs404() throws Exception {
        when(provisioningService.findManagedDatabase(DatabaseEngineType.MYSQL, "missing"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/mysql/databases/missing/connections").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void mysqlConnectionsNeverExposeSecrets() throws Exception {
        when(provisioningService.findManagedDatabase(DatabaseEngineType.MYSQL, "shop"))
                .thenReturn(Optional.of(mysqlDb()));
        when(mysqlEngine.resolveHost()).thenReturn("db.example.com:9816");
        when(mysqlEngine.isTls()).thenReturn(false);

        mockMvc.perform(get("/api/mysql/databases/shop/connections").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("password"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret"))))
                .andExpect(jsonPath("$.pooled").doesNotExist());
    }

    @Test
    void mongoConnectionsForAdmin() throws Exception {
        when(provisioningService.findManagedDatabase(DatabaseEngineType.MONGO, "shop"))
                .thenReturn(Optional.of(mongoDb()));
        when(mongoEngine.resolveConnectionHost()).thenReturn("db.example.com:9812");
        when(mongoEngine.resolveConnectionTls()).thenReturn(false);

        mockMvc.perform(get("/api/mongo/databases/shop/connections").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("shop"))
                .andExpect(jsonPath("$.direct.enabled").value(true))
                .andExpect(jsonPath("$.direct.authSource").value("shop"))
                .andExpect(jsonPath("$.direct.mode").value("DIRECT"));
    }

    @Test
    void mongoConnectionsRejectNonAdminReader() throws Exception {
        when(provisioningService.findManagedDatabase(DatabaseEngineType.MONGO, "shop"))
                .thenReturn(Optional.of(mongoDb()));
        when(mongoEngine.resolveConnectionHost()).thenReturn("db.example.com:9812");
        when(mongoEngine.resolveConnectionTls()).thenReturn(false);

        mockMvc.perform(get("/api/mongo/databases/shop/connections").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void mongoConnectionsAnonymouslyRedirected() throws Exception {
        mockMvc.perform(get("/api/mongo/databases/shop/connections"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void mongoConnectionsUnknownDatabaseIs404() throws Exception {
        when(provisioningService.findManagedDatabase(DatabaseEngineType.MONGO, "missing"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/mongo/databases/missing/connections").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin", false);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
