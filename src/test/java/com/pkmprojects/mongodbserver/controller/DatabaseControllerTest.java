package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.dto.CollectionInfo;
import com.pkmprojects.mongodbserver.dto.CollectionStats;
import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.DatabaseStats;
import com.pkmprojects.mongodbserver.dto.DatabaseUser;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.service.ExplorationService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import com.pkmprojects.mongodbserver.service.StatisticsService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for database provisioning, detail, password reset, and delete flows.
 */
@WebMvcTest(DatabaseController.class)
@Import({SecurityConfig.class, DatabaseControllerTest.SecurityTestConfig.class})
class DatabaseControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProvisioningService provisioningService;

    @MockitoBean
    private ExplorationService explorationService;

    @MockitoBean
    private StatisticsService statisticsService;

    private DatabaseInfo databaseInfo() {
        return new DatabaseInfo("myapp", "appuser", List.of("readWrite:myapp"), 1L, NOW, NOW, null, true, null, 0L);
    }

    // ── Provision form ──────────────────────────────────────────────────

    @Test
    void provisionFormRendersForAdmin() throws Exception {
        mockMvc.perform(get("/databases/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("provision"))
                .andExpect(model().attributeExists("form"))
                .andExpect(content().string(containsString("Provision a database")));
    }

    @Test
    void provisionFormRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/databases/new").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void provisionAsAdminRedirectsToDetail() throws Exception {
        when(provisioningService.provision(any(CreateDatabaseForm.class))).thenReturn(databaseInfo());

        mockMvc.perform(post("/databases")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("dbName", "myapp")
                        .param("userName", "appuser"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp"));

        verify(provisioningService).provision(any(CreateDatabaseForm.class));
    }

    @Test
    void provisionWithInvalidInputRerendersForm() throws Exception {
        mockMvc.perform(post("/databases")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("provision"))
                .andExpect(model().attributeHasFieldErrors("form", "dbName", "userName"));

        verify(provisioningService, never()).provision(any());
    }

    @Test
    void provisionWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/databases").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).provision(any());
    }

    // ── Detail ──────────────────────────────────────────────────────────

    @Test
    void detailRendersForAuthenticatedUser() throws Exception {
        when(provisioningService.getDatabase("myapp")).thenReturn(databaseInfo());
        when(explorationService.listCollections("myapp")).thenReturn(List.of(new CollectionInfo("users", 3L)));

        mockMvc.perform(get("/databases/myapp").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("database"))
                .andExpect(model().attributeExists("database", "collections"))
                .andExpect(content().string(containsString("users")));
    }

    @Test
    void detailOfMissingDatabaseReturns404() throws Exception {
        when(provisioningService.getDatabase("missing"))
                .thenThrow(new DatabaseNotFoundException("Database 'missing' does not exist"));

        mockMvc.perform(get("/databases/missing").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(content().string(containsString("Database &#39;missing&#39; does not exist")));
    }

    @Test
    void resetFormRequiresAdminRole() throws Exception {
        when(provisioningService.getDatabase("myapp")).thenReturn(databaseInfo());

        mockMvc.perform(get("/databases/myapp/reset").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/databases/myapp/reset").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"));
    }

    @Test
    void resetPasswordAsAdminRedirectsToDetail() throws Exception {
        when(provisioningService.resetPassword(eq("myapp"), any(ResetPasswordForm.class))).thenReturn(databaseInfo());

        mockMvc.perform(post("/databases/myapp/reset")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("password", "newsecret456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp"));

        verify(provisioningService).resetPassword(eq("myapp"), any(ResetPasswordForm.class));
    }

    @Test
    void resetPasswordWithOversizedPasswordRerendersForm() throws Exception {
        when(provisioningService.getDatabase("myapp")).thenReturn(databaseInfo());

        mockMvc.perform(post("/databases/myapp/reset")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("password", "x".repeat(129)))
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"))
                .andExpect(model().attributeHasFieldErrors("resetForm", "password"));

        verify(provisioningService, never()).resetPassword(any(), any());
    }

    @Test
    void resetPasswordAsUserIsForbidden() throws Exception {
        mockMvc.perform(post("/databases/myapp/reset")
                        .with(user("bob").roles("USER"))
                        .with(csrf())
                        .param("password", "newsecret456"))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).resetPassword(any(), any());
    }

    @Test
    void deleteConfirmRequiresAdminRole() throws Exception {
        when(provisioningService.getDatabase("myapp")).thenReturn(databaseInfo());

        mockMvc.perform(get("/databases/myapp/delete").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/databases/myapp/delete").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("delete-confirm"));
    }

    @Test
    void deleteAsAdminRedirectsToDashboard() throws Exception {
        mockMvc.perform(post("/databases/myapp/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        verify(provisioningService).delete("myapp");
    }

    @Test
    void deleteWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/databases/myapp/delete").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).delete(any());
    }

    // ── User management ─────────────────────────────────────────────────

    @Test
    void usersPageRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/databases/myapp/users").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());

        when(provisioningService.getDatabase("myapp")).thenReturn(databaseInfo());
        when(provisioningService.listUsers("myapp")).thenReturn(List.of());

        mockMvc.perform(get("/databases/myapp/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("users"));
    }

    @Test
    void usersPageListsUsers() throws Exception {
        when(provisioningService.getDatabase("myapp")).thenReturn(databaseInfo());
        when(provisioningService.listUsers("myapp")).thenReturn(List.of(
                new DatabaseUser("appuser", List.of("readWrite:myapp"), "myapp")));

        mockMvc.perform(get("/databases/myapp/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("users"))
                .andExpect(model().attributeExists("users"))
                .andExpect(content().string(containsString("appuser")));
    }

    @Test
    void usersPageForMissingDatabaseReturns404() throws Exception {
        when(provisioningService.getDatabase("missing"))
                .thenThrow(new DatabaseNotFoundException("Database 'missing' does not exist"));

        mockMvc.perform(get("/databases/missing/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokeUserAsAdminRedirectsToUsersPage() throws Exception {
        mockMvc.perform(post("/databases/myapp/users/otheruser/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp/users"));

        verify(provisioningService).revokeUser("myapp", "otheruser");
    }

    @Test
    void revokeUserAsUserIsForbidden() throws Exception {
        mockMvc.perform(post("/databases/myapp/users/otheruser/delete")
                        .with(user("bob").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).revokeUser(any(), any());
    }

    @Test
    void revokeUserWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/databases/myapp/users/otheruser/delete")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).revokeUser(any(), any());
    }

    // ── Statistics dashboard ────────────────────────────────────────────

    @Test
    void statsPageRendersForAuthenticatedUser() throws Exception {
        when(provisioningService.getDatabase("myapp")).thenReturn(databaseInfo());
        when(statisticsService.getDatabaseStats("myapp")).thenReturn(new DatabaseStats(
                "myapp", 1, 0, 100, 2048, 4096, 20, 2, 512,
                List.of(new CollectionStats("users", 100, 2048, 4096, 20, 2, 512))));

        mockMvc.perform(get("/databases/myapp/stats").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("stats"))
                .andExpect(model().attributeExists("database", "stats"))
                .andExpect(content().string(containsString("users")))
                .andExpect(content().string(containsString("2.0 KB")));
    }

    @Test
    void statsPageForMissingDatabaseReturns404() throws Exception {
        when(provisioningService.getDatabase("missing"))
                .thenThrow(new DatabaseNotFoundException("Database 'missing' does not exist"));

        mockMvc.perform(get("/databases/missing/stats").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin");
        }
    }
}
