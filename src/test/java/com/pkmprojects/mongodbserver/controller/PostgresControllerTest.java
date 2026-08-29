package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.DatabaseUser;
import com.pkmprojects.mongodbserver.dto.PostgresDatabaseStats;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.dto.TableInfo;
import com.pkmprojects.mongodbserver.dto.TableRowPage;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.service.BackupService;
import com.pkmprojects.mongodbserver.service.PostgresBackupService;
import com.pkmprojects.mongodbserver.service.PostgresExplorationService;
import com.pkmprojects.mongodbserver.service.PostgresStatisticsService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PostgresController.class)
@Import({SecurityConfig.class, PostgresControllerTest.SecurityTestConfig.class})
@TestPropertySource(properties = "app.postgres.enabled=true")
class PostgresControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ProvisioningService provisioningService;
    @MockitoBean private PostgresExplorationService explorationService;
    @MockitoBean private PostgresStatisticsService statisticsService;
    @MockitoBean private PostgresBackupService backupService;

    private DatabaseInfo dbInfo() {
        return new DatabaseInfo("myapp", DatabaseEngineType.POSTGRES, "myapp_user", List.of("CONNECT:myapp"), null, NOW, NOW, null, true, null, 0L);
    }

    // ── Home ────────────────────────────────────────────────────────

    @Test
    void homeRendersForAuthenticatedUser() throws Exception {
        when(provisioningService.listDatabases(DatabaseEngineType.POSTGRES)).thenReturn(List.of(dbInfo()));
        mockMvc.perform(get("/postgres").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("engine-home"));
    }

    @Test
    void homeRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/postgres"))
                .andExpect(status().is3xxRedirection());
    }

    // ── Provision ───────────────────────────────────────────────────

    @Test
    void provisionAsAdminRedirectsToDetail() throws Exception {
        when(provisioningService.provision(any())).thenReturn(dbInfo());
        mockMvc.perform(post("/postgres/databases")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("dbName", "myapp").param("userName", "myapp_user"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp"));
        verify(provisioningService).provision(any());
    }

    @Test
    void provisionWithInvalidInputRerendersForm() throws Exception {
        mockMvc.perform(post("/postgres/databases")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("provision-postgres"));
        verify(provisioningService, never()).provision(any());
    }

    @Test
    void provisionRequiresAdmin() throws Exception {
        mockMvc.perform(post("/postgres/databases")
                        .with(user("bob").roles("USER")).with(csrf())
                        .param("dbName", "myapp").param("userName", "myapp_user"))
                .andExpect(status().isForbidden());
    }

    @Test
    void provisionWithoutCsrfRejected() throws Exception {
        mockMvc.perform(post("/postgres/databases").with(user("admin").roles("ADMIN"))
                        .param("dbName", "myapp").param("userName", "myapp_user"))
                .andExpect(status().isForbidden());
    }

    // ── Detail ──────────────────────────────────────────────────────

    @Test
    void detailRendersForAuthenticatedUser() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        when(explorationService.listTables("myapp")).thenReturn(List.of(new TableInfo("users", 3L)));
        mockMvc.perform(get("/postgres/databases/myapp").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("database"))
                .andExpect(model().attributeExists("database", "tables"))
                .andExpect(content().string(containsString("users")));
    }

    @Test
    void detailOfMissingDatabaseReturns404() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "missing"))
                .thenThrow(new DatabaseNotFoundException("Database 'missing' does not exist"));
        mockMvc.perform(get("/postgres/databases/missing").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    // ── Tables ──────────────────────────────────────────────────────

    @Test
    void createTableAsAdminRedirects() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("tableName", "users").param("columns", "name,email"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp"));
        verify(explorationService).createTable("myapp", "users", List.of("name", "email"));
    }

    @Test
    void createTableRequiresAdmin() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables")
                        .with(user("bob").roles("USER")).with(csrf())
                        .param("tableName", "users"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dropTableAsAdminRedirects() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/delete")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp"));
        verify(explorationService).dropTable("myapp", "users");
    }

    @Test
    void truncateTableAsAdminRedirects() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/truncate")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(explorationService).truncateTable("myapp", "users");
    }

    @Test
    void insertRowAsAdminRedirects() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/rows")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("name", "alice").param("email", "alice@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp/tables/users"));
        verify(explorationService).insertRow(eq("myapp"), eq("users"), anyMap());
    }

    @Test
    void deleteRowAsAdminRedirects() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/rows/delete")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("ctid", "(0,1)"))
                .andExpect(status().is3xxRedirection());
        verify(explorationService).deleteRow("myapp", "users", "(0,1)");
    }

    @Test
    void tableRowsRendersForAuthenticatedUser() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        TableRowPage page = new TableRowPage("myapp", "users", 1, 50, 10, 1, List.of("name"), List.of(), false, false);
        when(explorationService.getRows("myapp", "users", 1)).thenReturn(page);
        mockMvc.perform(get("/postgres/databases/myapp/tables/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("table-rows"))
                .andExpect(model().attributeExists("tablePage"));
    }

    @Test
    void exportTableStreamsJson() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        mockMvc.perform(get("/postgres/databases/myapp/tables/users/export").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("myapp.users.json")));
        verify(explorationService).ensureTableExists("myapp", "users");
    }

    // ── Stats ───────────────────────────────────────────────────────

    @Test
    void statsPageRendersForAuthenticatedUser() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        when(statisticsService.getDatabaseStats("myapp")).thenReturn(
                new PostgresDatabaseStats("myapp", 1, 10, 8192, List.of()));
        mockMvc.perform(get("/postgres/databases/myapp/stats").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("stats-postgres"))
                .andExpect(model().attributeExists("pgStats"));
    }

    @Test
    void statsPageForMissingDatabaseReturns404() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "missing"))
                .thenThrow(new DatabaseNotFoundException("Database 'missing' does not exist"));
        mockMvc.perform(get("/postgres/databases/missing/stats").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    // ── Reset password ──────────────────────────────────────────────

    @Test
    void resetFormRequiresAdmin() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        mockMvc.perform(get("/postgres/databases/myapp/reset").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/postgres/databases/myapp/reset").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"));
    }

    @Test
    void resetPasswordAsAdminRedirects() throws Exception {
        when(provisioningService.resetPassword(eq(DatabaseEngineType.POSTGRES), eq("myapp"), any())).thenReturn(dbInfo());
        mockMvc.perform(post("/postgres/databases/myapp/reset")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("password", "newsecret456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp"));
    }

    @Test
    void resetPasswordWithOversizedPasswordRerendersForm() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        mockMvc.perform(post("/postgres/databases/myapp/reset")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("password", "x".repeat(129)))
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"));
        verify(provisioningService, never()).resetPassword(any(), any(), any());
    }

    // ── Delete ──────────────────────────────────────────────────────

    @Test
    void deleteConfirmRequiresAdmin() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        mockMvc.perform(get("/postgres/databases/myapp/delete").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/postgres/databases/myapp/delete").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("delete-confirm"));
    }

    @Test
    void deleteAsAdminRedirectsToPostgresHome() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/delete")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres"));
        verify(provisioningService).delete(DatabaseEngineType.POSTGRES, "myapp");
    }

    // ── Users ───────────────────────────────────────────────────────

    @Test
    void usersPageRequiresAdmin() throws Exception {
        mockMvc.perform(get("/postgres/databases/myapp/users").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        when(provisioningService.listUsers(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(List.of());
        mockMvc.perform(get("/postgres/databases/myapp/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("users"));
    }

    @Test
    void usersPageListsUsers() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        when(provisioningService.listUsers(DatabaseEngineType.POSTGRES, "myapp"))
                .thenReturn(List.of(new DatabaseUser("myapp_user", List.of("CONNECT:myapp"), "myapp")));
        mockMvc.perform(get("/postgres/databases/myapp/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("myapp_user")));
    }

    @Test
    void revokeUserAsAdminRedirects() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/users/other_user/delete")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp/users"));
        verify(provisioningService).revokeUser(DatabaseEngineType.POSTGRES, "myapp", "other_user");
    }

    @Test
    void revokeUserRequiresAdmin() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/users/other_user/delete")
                        .with(user("bob").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── Backup / Restore ────────────────────────────────────────────

    @Test
    void downloadBackupRequiresAdmin() throws Exception {
        mockMvc.perform(get("/postgres/databases/myapp/backup").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void downloadBackupStreamsForAdmin() throws Exception {
        mockMvc.perform(get("/postgres/databases/myapp/backup").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("backup-myapp-")));
        verify(backupService).requireDatabaseExists("myapp");
    }

    @Test
    void restoreFormRendersForAdmin() throws Exception {
        when(backupService.describeDatabase("myapp")).thenReturn(new BackupService.DatabaseBackupInfo("myapp", true, 2));
        mockMvc.perform(get("/postgres/databases/myapp/restore").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("restore"));
    }

    @Test
    void restoreFormRequiresAdmin() throws Exception {
        mockMvc.perform(get("/postgres/databases/myapp/restore").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void restoreWithoutFileShowsError() throws Exception {
        mockMvc.perform(multipart("/postgres/databases/myapp/restore")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp/restore"));
    }

    @Test
    void restoreWithoutConfirmationShowsError() throws Exception {
        mockMvc.perform(multipart("/postgres/databases/myapp/restore")
                        .file("file", "fake".getBytes())
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp/restore"));
    }

    @Test
    void restoreWithFileAndConfirmationSucceeds() throws Exception {
        when(backupService.restore(eq("myapp"), any(byte[].class), eq(true)))
                .thenReturn(new BackupService.RestoreResult("myapp", 1, 5));
        mockMvc.perform(multipart("/postgres/databases/myapp/restore")
                        .file("file", "fake-backup".getBytes())
                        .param("confirm", "true")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp"));
    }

    // ── Provision error mapping ─────────────────────────────────────

    @Test
    void provisionDuplicateDatabaseReturns409() throws Exception {
        when(provisioningService.provision(any())).thenThrow(new DatabaseAlreadyExistsException("already exists"));
        mockMvc.perform(post("/postgres/databases")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("dbName", "myapp").param("userName", "myapp_user"))
                .andExpect(status().isConflict());
    }

    @Test
    void provisionInvalidNameReturns400() throws Exception {
        when(provisioningService.provision(any())).thenThrow(new NameNotAllowedException("bad name"));
        mockMvc.perform(post("/postgres/databases")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("dbName", "MyApp").param("userName", "myapp_user"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void provisionFailureReturns500() throws Exception {
        when(provisioningService.provision(any())).thenThrow(new ProvisioningException("boom", new RuntimeException()));
        mockMvc.perform(post("/postgres/databases")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("dbName", "myapp").param("userName", "myapp_user"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void homeHandlesServiceFailureGracefully() throws Exception {
        when(provisioningService.listDatabases(DatabaseEngineType.POSTGRES)).thenThrow(new ProvisioningException("down", new RuntimeException()));
        mockMvc.perform(get("/postgres").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("engine-home"))
                .andExpect(model().attributeExists("postgresError"));
    }

    // ── Table error handling (flashError redirect) ───────────────────

    @Test
    void createTableWithInvalidNameRedirectsWithFlashError() throws Exception {
        doThrow(new NameNotAllowedException("bad table")).when(explorationService).createTable(eq("myapp"), eq("Bad-Name!"), anyList());
        mockMvc.perform(post("/postgres/databases/myapp/tables")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("tableName", "Bad-Name!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    void createTableWithoutCsrfRejected() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables")
                        .with(user("admin").roles("ADMIN"))
                        .param("tableName", "users"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dropTableWithoutCsrfRejected() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/delete")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dropTableFailureRedirectsWithFlashError() throws Exception {
        doThrow(new ProvisioningException("drop failed", new RuntimeException())).when(explorationService).dropTable("myapp", "users");
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/delete")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    void truncateTableWithoutCsrfRejected() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/truncate")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void insertRowWithoutCsrfRejected() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/rows")
                        .with(user("admin").roles("ADMIN"))
                        .param("name", "alice"))
                .andExpect(status().isForbidden());
    }

    @Test
    void insertRowWithEmptyValuesRedirectsWithFlashError() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/rows")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
        verify(explorationService, never()).insertRow(any(), any(), anyMap());
    }

    @Test
    void deleteRowWithoutCsrfRejected() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/tables/users/rows/delete")
                        .with(user("admin").roles("ADMIN"))
                        .param("ctid", "(0,1)"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tableRowsWithInvalidPageParamReturns400() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        mockMvc.perform(get("/postgres/databases/myapp/tables/users")
                        .param("page", "not-a-number")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tableRowsOfMissingTableReturns404() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        when(explorationService.getRows("myapp", "missing", 1)).thenThrow(new DatabaseNotFoundException("Table 'missing' does not exist"));
        mockMvc.perform(get("/postgres/databases/myapp/tables/missing").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportMissingTableReturns404() throws Exception {
        when(provisioningService.getDatabase(DatabaseEngineType.POSTGRES, "myapp")).thenReturn(dbInfo());
        doThrow(new DatabaseNotFoundException("Table 'missing' does not exist")).when(explorationService).ensureTableExists("myapp", "missing");
        mockMvc.perform(get("/postgres/databases/myapp/tables/missing/export").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/postgres/databases/myapp"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void tableRowsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/postgres/databases/myapp/tables/users"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void statsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/postgres/databases/myapp/stats"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void downloadBackupOfMissingDatabaseReturns404() throws Exception {
        doThrow(new DatabaseNotFoundException("Database 'missing' does not exist")).when(backupService).requireDatabaseExists("missing");
        mockMvc.perform(get("/postgres/databases/missing/backup").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void restoreWithInvalidBackupShowsFlashError() throws Exception {
        when(backupService.restore(eq("myapp"), any(byte[].class), eq(true)))
                .thenThrow(new NameNotAllowedException("bad backup"));
        mockMvc.perform(multipart("/postgres/databases/myapp/restore")
                        .file("file", "bad".getBytes())
                        .param("confirm", "true")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/postgres/databases/myapp/restore"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    void restoreWithProvisioningFailureShowsFlashError() throws Exception {
        when(backupService.restore(eq("myapp"), any(byte[].class), eq(true)))
                .thenThrow(new ProvisioningException("restore failed", new RuntimeException()));
        mockMvc.perform(multipart("/postgres/databases/myapp/restore")
                        .file("file", "bad".getBytes())
                        .param("confirm", "true")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    void restoreWithoutCsrfRejected() throws Exception {
        mockMvc.perform(multipart("/postgres/databases/myapp/restore")
                        .file("file", "data".getBytes())
                        .param("confirm", "true")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteWithoutCsrfRejected() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/delete")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokeUserWithoutCsrfRejected() throws Exception {
        mockMvc.perform(post("/postgres/databases/myapp/users/other_user/delete")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean AdminProperties adminProperties() { return new AdminProperties("admin", "admin"); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneOffset.UTC); }
    }
}
