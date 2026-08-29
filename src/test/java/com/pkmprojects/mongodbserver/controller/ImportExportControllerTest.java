package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.service.ImportExportService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/**
 * MVC slice tests for bulk collection export (read-only) and import (admin only).
 */
@WebMvcTest(ImportExportController.class)
@Import({SecurityConfig.class, ImportExportControllerTest.SecurityTestConfig.class})
@org.springframework.test.context.TestPropertySource(properties = "app.mongo.enabled=true")
class ImportExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImportExportService importExportService;

    // ── Export all (JSON) ───────────────────────────────────────────────

    @Test
    void exportAllJsonRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/databases/myapp/collections/users/export/all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verify(importExportService, never()).writeAllDocumentsAsJson(any(), any(), any());
    }

    @Test
    void exportAllJsonStreamsAttachment() throws Exception {
        doNothing().when(importExportService).writeAllDocumentsAsJson(eq("myapp"), eq("users"), any());

        MvcResult result = mockMvc.perform(get("/databases/myapp/collections/users/export/all").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"myapp.users.json\"")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());

        verify(importExportService).requireCollection("myapp", "users");
        verify(importExportService).writeAllDocumentsAsJson(eq("myapp"), eq("users"), any());
    }

    @Test
    void exportAllJsonOfMissingCollectionReturns404() throws Exception {
        doThrow(new DatabaseNotFoundException("Collection 'users' does not exist in database 'missing'"))
                .when(importExportService).requireCollection("missing", "users");

        mockMvc.perform(get("/databases/missing/collections/users/export/all").with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());

        verify(importExportService, never()).writeAllDocumentsAsJson(any(), any(), any());
    }

    // ── Export all (CSV) ────────────────────────────────────────────────

    @Test
    void exportAllCsvStreamsAttachment() throws Exception {
        doNothing().when(importExportService).writeAllDocumentsAsCsv(eq("myapp"), eq("users"), any());

        MvcResult result = mockMvc.perform(get("/databases/myapp/collections/users/export/all.csv").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"myapp.users.csv\"")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());

        verify(importExportService).requireCollection("myapp", "users");
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

    @Test
    void exportAllCsvOfMissingCollectionReturns404() throws Exception {
        doThrow(new DatabaseNotFoundException("Collection 'users' does not exist in database 'missing'"))
                .when(importExportService).requireCollection("missing", "users");

        mockMvc.perform(get("/databases/missing/collections/users/export/all.csv").with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());

        verify(importExportService, never()).writeAllDocumentsAsCsv(any(), any(), any());
    }

    // ── Import form ─────────────────────────────────────────────────────

    @Test
    void importFormRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/databases/myapp/collections/users/import").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/databases/myapp/collections/users/import").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("import"))
                .andExpect(model().attribute("dbName", "myapp"))
                .andExpect(model().attribute("collectionName", "users"));
    }

    @Test
    void importFormOfMissingCollectionReturns404() throws Exception {
        doThrow(new DatabaseNotFoundException("Collection 'users' does not exist in database 'missing'"))
                .when(importExportService).requireCollection("missing", "users");

        mockMvc.perform(get("/databases/missing/collections/users/import").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    // ── Import upload ───────────────────────────────────────────────────

    @Test
    void importAsAdminRedirectsToCollectionExplorer() throws Exception {
        when(importExportService.importDocuments(eq("myapp"), eq("users"), any(byte[].class)))
                .thenReturn(new ImportExportService.ImportResult("myapp", "users", 2));

        mockMvc.perform(multipart("/databases/myapp/collections/users/import")
                        .file(new MockMultipartFile("file", "users.json", "application/json",
                                "[{\"name\":\"alice\"},{\"name\":\"bob\"}]".getBytes(StandardCharsets.UTF_8)))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp/collections/users"))
                .andExpect(flash().attribute("flashSuccess", containsString("2")));

        verify(importExportService).importDocuments(eq("myapp"), eq("users"), any(byte[].class));
    }

    @Test
    void importWithoutFileRedirectsBackWithError() throws Exception {
        mockMvc.perform(multipart("/databases/myapp/collections/users/import")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp/collections/users/import"))
                .andExpect(flash().attributeExists("flashError"));

        verify(importExportService, never()).importDocuments(any(), any(), any(byte[].class));
    }

    @Test
    void importWithMalformedFileRedirectsBackWithError() throws Exception {
        doThrow(new NameNotAllowedException("Could not parse the uploaded file"))
                .when(importExportService).importDocuments(eq("myapp"), eq("users"), any(byte[].class));

        mockMvc.perform(multipart("/databases/myapp/collections/users/import")
                        .file(new MockMultipartFile("file", "users.json", "application/json",
                                "garbage".getBytes(StandardCharsets.UTF_8)))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp/collections/users/import"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    void importAsUserIsForbidden() throws Exception {
        mockMvc.perform(multipart("/databases/myapp/collections/users/import")
                        .file(new MockMultipartFile("file", "users.json", "application/json",
                                "[{}]".getBytes(StandardCharsets.UTF_8)))
                        .with(user("bob").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(importExportService, never()).importDocuments(any(), any(), any(byte[].class));
    }

    @Test
    void importWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(multipart("/databases/myapp/collections/users/import")
                        .file(new MockMultipartFile("file", "users.json", "application/json",
                                "[{}]".getBytes(StandardCharsets.UTF_8)))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        verify(importExportService, never()).importDocuments(any(), any(), any(byte[].class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin");
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
