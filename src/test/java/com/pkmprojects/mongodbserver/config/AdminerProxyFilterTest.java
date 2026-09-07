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
 * parse from the real 6.0.1 markup, and an unreachable upstream still answers
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
    void unreachableUpstreamStillAnswers502() throws Exception {
        AdminerProxyFilter unreachable =
                new AdminerProxyFilter("http://127.0.0.1:9", "root", "secret",
                        java.net.http.HttpClient.newHttpClient());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/adminer/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        unreachable.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(502);
    }
}
