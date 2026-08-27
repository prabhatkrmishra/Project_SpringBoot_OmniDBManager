package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for the dashboard: rendering and listing.
 */
@WebMvcTest({DashboardController.class, LoginController.class})
@Import({SecurityConfig.class, DashboardControllerTest.SecurityTestConfig.class})
class DashboardControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProvisioningService provisioningService;

    @MockitoBean
    private AuditLogRepository auditLogRepository;

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginPageRendersWithCsrfToken() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void loginAsAdminSucceeds() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "admin")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void dashboardListsDatabasesAndActivity() throws Exception {
        when(provisioningService.listDatabases(DatabaseEngineType.MONGO)).thenReturn(List.of(
                new DatabaseInfo("myapp", DatabaseEngineType.MONGO, "appuser", List.of("readWrite:myapp"), 2L, NOW, NOW, null, true, null, 0L)));
        when(provisioningService.listDatabases(DatabaseEngineType.POSTGRES)).thenReturn(List.of());
        when(auditLogRepository.findTop10ByOrderByPerformedAtDesc()).thenReturn(List.of(
                new AuditEvent(AuditEvent.PROVISION, "myapp", "appuser", "admin", NOW)));

        mockMvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("databases", "recentActivity"))
                .andExpect(content().string(containsString("myapp")))
                .andExpect(content().string(containsString("Recent activity")));
    }

    @Test
    void dashboardShowsNewDatabaseLink() throws Exception {
        when(provisioningService.listDatabases(DatabaseEngineType.MONGO)).thenReturn(List.of());
        when(provisioningService.listDatabases(DatabaseEngineType.POSTGRES)).thenReturn(List.of());
        when(auditLogRepository.findTop10ByOrderByPerformedAtDesc()).thenReturn(List.of());

        mockMvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/provision")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin");
        }
    }
}
