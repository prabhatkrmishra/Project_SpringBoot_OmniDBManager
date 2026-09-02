package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.service.BackupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for backup download and restore upload (admin only).
 */
@WebMvcTest(BackupController.class)
@Import({SecurityConfig.class, BackupControllerTest.SecurityTestConfig.class})
@org.springframework.test.context.TestPropertySource(properties = "app.mongo.enabled=true")
class BackupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupService backupService;

    // ── Backup download ─────────────────────────────────────────────────

    @Test
    void backupDownloadRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/databases/myapp/backup").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());

        verify(backupService, never()).writeBackup(any(), any());
    }

    @Test
    void backupDownloadStreamsGzipAttachment() throws Exception {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8));
            return new BackupService.BackupResult("myapp", 1, 2);
        }).when(backupService).writeBackup(eq("myapp"), any(OutputStream.class));

        MvcResult result = mockMvc.perform(get("/databases/myapp/backup").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"backup-myapp-")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());

        // The streaming body is written on a background thread, so wait for it
        // rather than racing it.
        assertThat(awaitStreamedBody(result)).contains("formatVersion");

        verify(backupService).requireDatabaseExists("myapp");
    }

    @Test
    void backupDownloadOfMissingDatabaseReturns404() throws Exception {
        doThrow(new DatabaseNotFoundException("Database 'missing' does not exist"))
                .when(backupService).requireDatabaseExists("missing");

        mockMvc.perform(get("/databases/missing/backup").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());

        verify(backupService, never()).writeBackup(any(), any());
    }

    // ── Restore form ────────────────────────────────────────────────────

    @Test
    void restoreFormRequiresAdminRole() throws Exception {
        when(backupService.describeDatabase("myapp"))
                .thenReturn(new BackupService.DatabaseBackupInfo("myapp", true, 1));

        mockMvc.perform(get("/databases/myapp/restore").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/databases/myapp/restore").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("restore"));
    }

    @Test
    void restoreFormShowsDatabaseInfo() throws Exception {
        when(backupService.describeDatabase("myapp"))
                .thenReturn(new BackupService.DatabaseBackupInfo("myapp", true, 3));

        mockMvc.perform(get("/databases/myapp/restore").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("restore"))
                .andExpect(model().attributeExists("database"))
                .andExpect(content().string(containsString("3")));
    }

    // ── Restore upload ──────────────────────────────────────────────────

    @Test
    void restoreAsAdminRedirectsToDatabase() throws Exception {
        when(backupService.restore(eq("myapp"), any(byte[].class), eq(true)))
                .thenReturn(new BackupService.RestoreResult("myapp", 2, 10));

        mockMvc.perform(multipart("/databases/myapp/restore")
                        .file(new MockMultipartFile("file", "backup.json.gz", "application/gzip",
                                "{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8)))
                        .param("confirm", "true")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp"));

        verify(backupService).restore(eq("myapp"), any(byte[].class), eq(true));
    }

    @Test
    void restoreWithoutConfirmationRedirectsBackWithError() throws Exception {
        mockMvc.perform(multipart("/databases/myapp/restore")
                        .file(new MockMultipartFile("file", "backup.json.gz", "application/gzip",
                                "{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8)))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp/restore"))
                .andExpect(flash().attributeExists("flashError"));

        verify(backupService, never()).restore(any(), any(), anyBoolean());
    }

    @Test
    void restoreWithoutFileRedirectsBackWithError() throws Exception {
        mockMvc.perform(multipart("/databases/myapp/restore")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp/restore"))
                .andExpect(flash().attributeExists("flashError"));

        verify(backupService, never()).restore(any(), any(), anyBoolean());
    }

    @Test
    void restoreWithMalformedFileRedirectsBackWithError() throws Exception {
        doThrow(new NameNotAllowedException("Backup file could not be read or is not a valid backup"))
                .when(backupService).restore(eq("myapp"), any(byte[].class), eq(true));

        mockMvc.perform(multipart("/databases/myapp/restore")
                        .file(new MockMultipartFile("file", "backup.json.gz", "application/gzip",
                                "garbage".getBytes(StandardCharsets.UTF_8)))
                        .param("confirm", "true")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp/restore"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    void restoreAsUserIsForbidden() throws Exception {
        mockMvc.perform(multipart("/databases/myapp/restore")
                        .file(new MockMultipartFile("file", "backup.json.gz", "application/gzip",
                                "{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8)))
                        .param("confirm", "true")
                        .with(user("bob").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(backupService, never()).restore(any(), any(), anyBoolean());
    }

    @Test
    void restoreWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(multipart("/databases/myapp/restore")
                        .file(new MockMultipartFile("file", "backup.json.gz", "application/gzip",
                                "{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8)))
                        .param("confirm", "true")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        verify(backupService, never()).restore(any(), any(), anyBoolean());
    }

    private static String awaitStreamedBody(MvcResult result) throws Exception {
        String content;
        long deadline = System.currentTimeMillis() + 5000;
        do {
            content = result.getResponse().getContentAsString();
            if (!content.isEmpty()) {
                return content;
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        return content;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin", false);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}