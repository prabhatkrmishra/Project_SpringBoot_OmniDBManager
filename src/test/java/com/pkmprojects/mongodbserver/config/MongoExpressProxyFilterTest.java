package com.pkmprojects.mongodbserver.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MongoExpressProxyFilter}: a request path that cannot
 * form a valid upstream URI is answered with 400 (never an unhandled 500),
 * without any outbound call; and the configured basic-auth credential reaches
 * mongo-express only when injection is left on.
 */
class MongoExpressProxyFilterTest {

    private static MongoExpressProxyFilter filter(boolean injectAuth) {
        return new MongoExpressProxyFilter("http://127.0.0.1:9814/mongo-express",
                "admin", "admin", HttpClient.newHttpClient(), injectAuth);
    }

    @Test
    void rejectsUnparseableRequestPathWith400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mongo-express/a b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("cannot be proxied");
    }

    @Test
    void rejectsInvalidPercentSequenceWith400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mongo-express/%zz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
    }

    // ── credential injection opt-out ──────────────────────────────────

    @Test
    void injectionIsOnByDefault() {
        assertThat(filter(true).isInjectAuth()).isTrue();
        assertThat(filter(false).isInjectAuth()).isFalse();
    }

    @Test
    void disabledInjectionSendsNoAuthorizationUpstream() throws Exception {
        // The credential defaults to admin/admin and would otherwise be attached
        // to every proxied call, making any authenticated admin of this app
        // whoever that account can reach.
        List<String> seen = new ArrayList<>();
        try (Upstream upstream = Upstream.recording(seen)) {
            MongoExpressProxyFilter off = new MongoExpressProxyFilter(
                    upstream.baseUrl(), "admin", "admin", HttpClient.newHttpClient(), false);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mongo-express/");
            MockHttpServletResponse response = new MockHttpServletResponse();

            off.doFilter(request, response, new MockFilterChain());
        }
        assertThat(seen).isNotEmpty();
        assertThat(seen).noneMatch(h -> h.startsWith("Basic "));
    }

    @Test
    void enabledInjectionStillSendsTheConfiguredCredential() throws Exception {
        // Counterpart to the test above, so the flag cannot silently become a no-op.
        List<String> seen = new ArrayList<>();
        try (Upstream upstream = Upstream.recording(seen)) {
            MongoExpressProxyFilter on = new MongoExpressProxyFilter(
                    upstream.baseUrl(), "admin", "admin", HttpClient.newHttpClient(), true);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mongo-express/");
            MockHttpServletResponse response = new MockHttpServletResponse();

            on.doFilter(request, response, new MockFilterChain());
        }
        assertThat(seen).contains("Basic YWRtaW46YWRtaW4=");
    }

    /** Minimal loopback upstream that records the Authorization header it receives. */
    private record Upstream(HttpServer server) implements AutoCloseable {
        static Upstream recording(List<String> authHeaders) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    authHeaders.add(auth == null ? "" : auth);
                    byte[] page = "<html>mongo-express</html>".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, page.length);
                    exchange.getResponseBody().write(page);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return new Upstream(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
