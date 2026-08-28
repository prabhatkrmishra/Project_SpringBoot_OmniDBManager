package com.pkmprojects.mongodbserver.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProvisionRateLimitFilterTest {

    @Mock
    private LoginRateLimiter rateLimiter;
    @Mock
    private MockFilterChain filterChain;

    private final ProvisionRateLimitProperties props = new ProvisionRateLimitProperties(5, Duration.ofMinutes(1), false);
    private final ProvisionRateLimitProperties propsWithXff = new ProvisionRateLimitProperties(5, Duration.ofMinutes(1), true);

    private ProvisionRateLimitFilter filter(ProvisionRateLimitProperties p) {
        return new ProvisionRateLimitFilter(rateLimiter, p);
    }

    // ── shouldNotFilter ─────────────────────────────────────────────

    @Test
    void getRequestsAreNotFiltered() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/postgres/databases");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        verify(filterChain).doFilter(req, res);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void postProvisionPostgresIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), eq(5), eq(Duration.ofMinutes(1)));
        verify(filterChain).doFilter(req, res);
    }

    @Test
    void postProvisionPostgresWithTrailingSlashIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        verify(rateLimiter).isAllowed(anyString(), anyInt(), any());
    }

    @Test
    void postProvisionMongoIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/mongo/databases");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:MONGO"), anyInt(), any());
    }

    @Test
    void postProvisionLegacyIsFilteredAsMongo() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/databases");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:MONGO"), anyInt(), any());
    }

    @Test
    void postResetPostgresIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/reset");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(anyString(), anyInt(), any());
    }

    @Test
    void postResetMongoIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/mongo/databases/myapp/reset");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:MONGO"), anyInt(), any());
    }

    @Test
    void postDeletePostgresIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/delete");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), anyInt(), any());
    }

    @Test
    void postDeletePostgresTableIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/tables/users/delete");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), anyInt(), any());
    }

    @Test
    void postDeletePostgresRowIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/tables/users/rows/delete");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), anyInt(), any());
    }

    @Test
    void postCreateTableIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/tables");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), anyInt(), any());
    }

    @Test
    void postTruncateTableIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/tables/users/truncate");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), anyInt(), any());
    }

    @Test
    void postInsertRowIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/tables/users/rows");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), anyInt(), any());
    }

    @Test
    void postBackupPostgresIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/backup");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), anyInt(), any());
    }

    @Test
    void postRestorePostgresIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases/myapp/restore");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:POSTGRES"), anyInt(), any());
    }

    @Test
    void postCollectionCreateIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/databases/myapp/collections");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:MONGO"), anyInt(), any());
    }

    @Test
    void postImportIsFiltered() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/databases/myapp/collections/users/import");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed(eq("127.0.0.1:MONGO"), anyInt(), any());
    }

    @Test
    void postUnrelatedPathIsNotFiltered() throws Exception {
        MockHttpServletRequest req = post("/postgres/databases/myapp/tables/users/export");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        verify(filterChain).doFilter(req, res);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void postOtherUnrelatedIsNotFiltered() throws Exception {
        MockHttpServletRequest req = post("/api/other");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        verify(filterChain).doFilter(req, res);
        verifyNoInteractions(rateLimiter);
    }

    // ── rate limit enforcement ──────────────────────────────────────

    @Test
    void blocksWith429AndRetryAfterWhenLimitExceeded() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(false);
        MockHttpServletRequest req = post("/postgres/databases");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
        assertThat(res.getContentType()).contains("text/plain");
        assertThat(res.getContentAsString()).contains("POSTGRES");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void blocksMongoWithCorrectEngineInMessage() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(false);
        MockHttpServletRequest req = post("/mongo/databases");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(props).doFilter(req, res, filterChain);
        assertThat(res.getContentAsString()).contains("MONGO");
    }

    @Test
    void perEngineIsolationPostgresDoesNotAffectMongoKey() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest pgReq = post("/postgres/databases");
        pgReq.setRemoteAddr("1.2.3.4");
        filter(props).doFilter(pgReq, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed("1.2.3.4:POSTGRES", 5, Duration.ofMinutes(1));

        MockHttpServletRequest mongoReq = post("/mongo/databases");
        mongoReq.setRemoteAddr("1.2.3.4");
        filter(props).doFilter(mongoReq, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed("1.2.3.4:MONGO", 5, Duration.ofMinutes(1));
    }

    // ── X-Forwarded-For ─────────────────────────────────────────────

    @Test
    void ignoresSpoofedForwardedForByDefault() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases");
        req.addHeader("X-Forwarded-For", "6.6.6.6");
        filter(props).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed("127.0.0.1:POSTGRES", 5, Duration.ofMinutes(1));
    }

    @Test
    void honorsForwardedForWhenTrusted() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases");
        req.addHeader("X-Forwarded-For", "6.6.6.6, 7.7.7.7");
        filter(propsWithXff).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed("6.6.6.6:POSTGRES", 5, Duration.ofMinutes(1));
    }

    @Test
    void honorsForwardedForTrimsSpaces() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest req = post("/postgres/databases");
        req.addHeader("X-Forwarded-For", "  6.6.6.6  , 7.7.7.7");
        filter(propsWithXff).doFilter(req, new MockHttpServletResponse(), filterChain);
        verify(rateLimiter).isAllowed("6.6.6.6:POSTGRES", 5, Duration.ofMinutes(1));
    }

    private MockHttpServletRequest post(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setServletPath(uri);
        req.setRemoteAddr("127.0.0.1");
        return req;
    }
}
