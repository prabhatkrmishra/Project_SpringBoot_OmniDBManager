package com.pkmprojects.mongodbserver.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminerProxyFilter}: cookie scoping keeps the Adminer
 * session pair while the Spring session never leaks upstream, login tokens
 * parse from the real 6.0.1 markup, spoofed forwarding headers never cross
 * the proxy boundary, and an unreachable upstream still answers
 * 502 (single sign-on failure falls back instead of failing the request).
 */
class AdminerProxyFilterTest {

    private final AdminerProxyFilter filter =
            new AdminerProxyFilter("http://127.0.0.1:9815", "root", "secret",
                    java.net.http.HttpClient.newHttpClient());

    @Test
    void rejectsUnparseableRequestPathWith400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/adminer/a b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("cannot be proxied");
    }

    @Test
    void forwardsOnlyAdminerCookiesUpstream() {
        Cookie[] cookies = {
                new Cookie("JSESSIONID", "spring-session"),
                new Cookie("adminer_sid", "sid123"),
                new Cookie("other", "drop-me"),
                new Cookie("adminer_key", "key456"),
        };

        assertThat(AdminerProxyFilter.adminerCookieHeader(cookies))
                .isEqualTo("adminer_sid=sid123; adminer_key=key456");
    }

    @Test
    void noAdminerCookiesMeansNoCookieHeader() {
        assertThat(AdminerProxyFilter.adminerCookieHeader(null)).isNull();
        assertThat(AdminerProxyFilter.adminerCookieHeader(new Cookie[0])).isNull();
        assertThat(AdminerProxyFilter.adminerCookieHeader(
                new Cookie[]{new Cookie("JSESSIONID", "spring-session")})).isNull();
    }

    @Test
    void dropsNonAdminerSetCookies() {
        assertThat(AdminerProxyFilter.rewriteSetCookie("JSESSIONID=abc; path=/; HttpOnly")).isNull();
        assertThat(AdminerProxyFilter.rewriteSetCookie(null)).isNull();
        assertThat(AdminerProxyFilter.rewriteSetCookie("not-a-cookie")).isNull();
    }

    @Test
    void scopesAdminerSetCookiePathToProxyPrefix() {
        assertThat(AdminerProxyFilter.rewriteSetCookie("adminer_sid=abc; path=/; HttpOnly"))
                .isEqualTo("adminer_sid=abc; path=/adminer; HttpOnly");
        assertThat(AdminerProxyFilter.rewriteSetCookie("adminer_key=abc; Path=/; SameSite=lax"))
                .contains("Path=/adminer");
    }

    @Test
    void parsesLoginTokenFromAdminerMarkup() {
        assertThat(AdminerProxyFilter.parseLoginToken(
                "<input type='hidden' name='token' value='1005202:426778'>"))
                .isEqualTo("1005202:426778");
        assertThat(AdminerProxyFilter.parseLoginToken(
                "<input type=\"hidden\" name=\"token\" value=\"abc:123\">"))
                .isEqualTo("abc:123");
        assertThat(AdminerProxyFilter.parseLoginToken("<html>no token here</html>")).isNull();
        assertThat(AdminerProxyFilter.parseLoginToken(null)).isNull();
    }

    @Test
    void setCookiesRoundTripToCookieHeader() {
        List<String> setCookies = List.of(
                "adminer_sid=sid123; path=/; HttpOnly",
                "adminer_key=key456; path=/; HttpOnly",
                "JSESSIONID=drop; path=/; HttpOnly");

        Map<String, String> parsed = AdminerProxyFilter.setCookiesToMap(setCookies);

        assertThat(parsed).containsOnlyKeys("adminer_sid", "adminer_key");
        assertThat(AdminerProxyFilter.cookieHeaderFromSetCookies(setCookies))
                .isEqualTo("adminer_sid=sid123; adminer_key=key456");
    }

    @Test
    void detectsLoginPageOnlyForHtmlLoginMarkup() {
        String loginHtml = "<form><input name=\"auth[username]\">"
                + "<input type='hidden' name='token' value='1:2'></form>";
        assertThat(AdminerProxyFilter.isLoginPage("text/html; charset=utf-8",
                loginHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isTrue();
        assertThat(AdminerProxyFilter.isLoginPage("application/json",
                loginHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat(AdminerProxyFilter.isLoginPage("text/html",
                "<html>databases here</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat(AdminerProxyFilter.isLoginPage(null, loginHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat(AdminerProxyFilter.isLoginPage("text/html", null)).isFalse();
        assertThat(AdminerProxyFilter.isLoginPage("text/html", new byte[65537])).isFalse();
    }

    @Test
    void dropsSpoofedForwardedPrefixHeader() {
        // Adminer honors X-Forwarded-Prefix for cookie paths (CVE-2026-16434
        // class); a client-supplied value must never reach the upstream.
        assertThat(AdminerProxyFilter.isNonForwardedHeader("X-Forwarded-Prefix")).isTrue();
        assertThat(AdminerProxyFilter.isNonForwardedHeader("x-forwarded-prefix")).isTrue();
        assertThat(AdminerProxyFilter.isNonForwardedHeader("X-Forwarded-For")).isFalse();
        assertThat(AdminerProxyFilter.isNonForwardedHeader(null)).isFalse();
    }

    @Test
    void unreachableUpstreamStillAnswers502() throws Exception {
        AdminerProxyFilter unreachable =
                new AdminerProxyFilter("http://127.0.0.1:9", "root", "secret",
                        java.net.http.HttpClient.newHttpClient());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/adminer/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        unreachable.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(502);
    }

    @Test
    void ssoFallbackStillServesUpstreamOr502WithoutCredentials() throws Exception {
        // SSO failure must fall back (never throw, never leak
        // credentials) — against a dead upstream that means a 502 with a
        // safe message; the WARN signal itself is verified by log
        // inspection in live validation, not asserted on content here.
        AdminerProxyFilter unreachable =
                new AdminerProxyFilter("http://127.0.0.1:9", "root", "s3cr3t-pw",
                        java.net.http.HttpClient.newHttpClient());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/adminer/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        unreachable.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).doesNotContain("s3cr3t-pw");
    }

    @Test
    void successfulSsoRedirectsBareLoadToSessionUrl() throws Exception {
        // Bare / renders Adminer's login form even for a live session, so a
        // fresh SSO on a bare load must redirect the browser to Adminer's
        // own post-login session URL instead of proxying / (which would look
        // exactly like SSO failed). Stub upstream speaks just enough Adminer:
        // GET / serves a token page, POST answers 302 + session + Location.
        com.sun.net.httpserver.HttpServer upstream =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            try {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    byte[] page = ("<html><form><input name=\"auth[username]\">"
                            + "<input type='hidden' name='token' value='7:stub'></form></html>")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    exchange.getResponseHeaders().add("Set-Cookie", "adminer_sid=init; path=/; HttpOnly");
                    exchange.sendResponseHeaders(200, page.length);
                    exchange.getResponseBody().write(page);
                } else {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().add("Set-Cookie", "adminer_sid=sess123; path=/; HttpOnly");
                    exchange.getResponseHeaders().add("Set-Cookie", "adminer_key=key456; path=/; HttpOnly");
                    exchange.getResponseHeaders().add("Location", "?pgsql=postgres&username=root");
                    exchange.sendResponseHeaders(302, -1);
                }
            } finally {
                exchange.close();
            }
        });
        upstream.start();
        try {
            int port = upstream.getAddress().getPort();
            AdminerProxyFilter filter = new AdminerProxyFilter("http://127.0.0.1:" + port, "root", "pw",
                    java.net.http.HttpClient.newHttpClient());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/adminer/");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(302);
            assertThat(response.getRedirectedUrl())
                    .isEqualTo("/adminer/?pgsql=postgres&username=root");
            // Session cookies still reach the browser scoped under /adminer.
            assertThat(response.getHeaders("Set-Cookie").toString()).contains("adminer_sid");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void defaultConstructorKeepsSuperuserSsoEnabled() {
        // The 4-arg constructor is what every pre-existing caller and test uses.
        // It must stay on: flipping the default would silently revoke the
        // convenience login for every deployment that never set the flag.
        AdminerProxyFilter defaulted =
                new AdminerProxyFilter("http://127.0.0.1:9815", "root", "pw",
                        java.net.http.HttpClient.newHttpClient());

        assertThat(defaulted.isSsoEnabled()).isTrue();
    }

    @Test
    void ssoDisabledNeverSendsRootCredentialsUpstream() throws Exception {
        // ADMINER_SSO_ENABLED=false must leave the proxy working while making
        // the superuser auto-login impossible: no POST upstream at all, so the
        // root password never leaves the process, and Adminer's own login form
        // is served verbatim.
        List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        com.sun.net.httpserver.HttpServer upstream =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                seen.add(exchange.getRequestMethod() + " " + new String(body, java.nio.charset.StandardCharsets.UTF_8));
                byte[] page = ("<html><form><input name=\"auth[username]\">"
                        + "<input type='hidden' name='token' value='7:stub'></form></html>")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, page.length);
                exchange.getResponseBody().write(page);
            } finally {
                exchange.close();
            }
        });
        upstream.start();
        try {
            AdminerProxyFilter off = new AdminerProxyFilter(
                    "http://127.0.0.1:" + upstream.getAddress().getPort(),
                    "root", "sup3rs3cr3t", java.net.http.HttpClient.newHttpClient(), false);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/adminer/");
            MockHttpServletResponse response = new MockHttpServletResponse();

            off.doFilter(request, response, new MockFilterChain());

            assertThat(off.isSsoEnabled()).isFalse();
            // Exactly one proxied GET; no login POST was attempted.
            assertThat(seen).hasSize(1).allMatch(r -> r.startsWith("GET"));
            assertThat(seen.toString()).doesNotContain("sup3rs3cr3t");
            // Adminer's login form reaches the browser untouched, and no
            // session cookie was minted server-side.
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains("auth[username]");
            assertThat(response.getHeaders("Set-Cookie").toString()).doesNotContain("adminer_sid");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void ssoEnabledStillPerformsTheSuperuserLogin() throws Exception {
        // Counterpart to the opt-out test: proves the flag is wired to real
        // behaviour and not a no-op — with SSO on, the same stub upstream
        // receives the credential POST.
        List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        com.sun.net.httpserver.HttpServer upstream =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                seen.add(exchange.getRequestMethod() + " " + new String(body, java.nio.charset.StandardCharsets.UTF_8));
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    byte[] page = ("<html><form><input name=\"auth[username]\">"
                            + "<input type='hidden' name='token' value='7:stub'></form></html>")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, page.length);
                    exchange.getResponseBody().write(page);
                } else {
                    exchange.getResponseHeaders().add("Set-Cookie", "adminer_sid=sess123; path=/; HttpOnly");
                    exchange.getResponseHeaders().add("Location", "?pgsql=postgres&username=root");
                    exchange.sendResponseHeaders(302, -1);
                }
            } finally {
                exchange.close();
            }
        });
        upstream.start();
        try {
            AdminerProxyFilter on = new AdminerProxyFilter(
                    "http://127.0.0.1:" + upstream.getAddress().getPort(),
                    "root", "sup3rs3cr3t", java.net.http.HttpClient.newHttpClient(), true);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/adminer/");
            MockHttpServletResponse response = new MockHttpServletResponse();

            on.doFilter(request, response, new MockFilterChain());

            assertThat(on.isSsoEnabled()).isTrue();
            assertThat(seen).hasSize(2);
            assertThat(seen.get(1)).startsWith("POST").contains("sup3rs3cr3t");
        } finally {
            upstream.stop(0);
        }
    }
}
