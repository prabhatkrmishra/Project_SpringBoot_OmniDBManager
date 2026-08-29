package com.pkmprojects.mongodbserver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reverse proxy exposing the bundled mongo-express UI through the admin app.
 *
 * <p>Requests under {@code /mongo-express/**} are forwarded to the mongo-express
 * container (reachable at {@code app.mongo-express.base-url}) with its HTTP basic
 * auth injected from {@code app.mongo-express.username} / {@code app.mongo-express.password}.
 * Because this filter runs inside the authenticated web context, mongo-express is only
 * reachable through a signed-in admin session; its own basic auth stays on as a second
 * layer, and the container port is bound to loopback only. Only loaded when
 * {@code app.mongo.enabled=true}.</p>
 */
@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoExpressProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MongoExpressProxyFilter.class);

    /**
     * URL prefix under which requests are proxied to mongo-express.
     */
    private static final String PROXY_PREFIX = "/mongo-express";

    /**
     * Hop-by-hop headers that must not be forwarded to the upstream server. They are
     * stripped from inbound requests and from the upstream response alike, so each hop
     * manages its own connection semantics.
     */
    private static final Set<String> NON_FORWARDED_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    /**
     * Base URI of the upstream mongo-express instance.
     */
    private final URI targetBase;

    /**
     * Pre-encoded {@code Basic} authorization header for the mongo-express credentials.
     */
    private final String authorization;

    /**
     * Shared HTTP client used for every proxied request.
     */
    private final HttpClient http;

    /**
     * Builds the proxy filter from the configured mongo-express connection settings.
     *
     * @param baseUrl  base URL of the mongo-express container
     * @param username mongo-express basic-auth username
     * @param password mongo-express basic-auth password
     */
    public MongoExpressProxyFilter(@Value("${app.mongo-express.base-url}") String baseUrl,
                                    @Value("${app.mongo-express.username}") String username,
                                    @Value("${app.mongo-express.password}") String password,
                                    HttpClient httpClient) {
        this.targetBase = URI.create(baseUrl);
        String token = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.authorization = "Basic " + token;
        this.http = httpClient;
    }

    /**
     * Skips the proxy unless the request path is under {@value #PROXY_PREFIX}.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROXY_PREFIX);
    }

    /**
     * Forwards a proxied request to mongo-express, mirroring its status, headers and body
     * back to the client. Request and response headers are copied with hop-by-hop headers
     * excluded, and {@code Location} headers are rewritten so redirects stay within the
     * proxy prefix. Failures are reported as a {@code 502 Bad Gateway}.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI().substring(PROXY_PREFIX.length());
        String target = targetBase + path;
        if (request.getQueryString() != null) {
            target += "?" + request.getQueryString();
        }

        byte[] body = request.getInputStream().readAllBytes();
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(target))
                    .header("Authorization", authorization)
                    .timeout(Duration.ofSeconds(60));
        } catch (IllegalArgumentException e) {
            // The raw request path/query contains characters that cannot form a
            // valid target URI (space, bad %-sequence, ...). That is a bad
            // request, not a gateway failure - answer 400 instead of letting the
            // exception surface as a 500.
            log.debug("Cannot proxy request with unparseable target '{}'", target);
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "The requested path cannot be proxied to Mongo Express");
            return;
        }

        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (NON_FORWARDED_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            // Never forward the admin session's own Authorization/Cookie headers
            // upstream: the proxy injects its own basic-auth Authorization, and the
            // admin session cookie must not leak to mongo-express.
            if (name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("cookie")) {
                continue;
            }
            request.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        }
        builder.method(request.getMethod(), body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        try {
            HttpResponse<byte[]> upstream = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            response.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((name, values) -> {
                String lower = name.toLowerCase();
                if (NON_FORWARDED_HEADERS.contains(lower)) {
                    return;
                }
                for (String value : values) {
                    response.addHeader(name, lower.equals("location") ? rewriteLocation(value) : value);
                }
            });
            byte[] upstreamBody = upstream.body();
            response.setContentLength(upstreamBody.length);
            response.getOutputStream().write(upstreamBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Mongo Express proxy request was interrupted", e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "Mongo Express proxy request was interrupted");
        } catch (ConnectException e) {
            log.warn("Mongo Express is not reachable at {}", targetBase, e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "Mongo Express is not reachable. Is the container running?");
        } catch (IOException e) {
            log.error("Mongo Express proxy request to {} failed", target, e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "Mongo Express proxy request failed");
        }
    }

    /**
     * Rewrites an upstream {@code Location} header: an absolute URL pointing at the
     * mongo-express origin is converted to a proxy-relative path so redirects route back
     * through this filter. Other values are returned unchanged.
     */
    private String rewriteLocation(String value) {
        if (value == null) {
            return value;
        }
        String origin = targetBase.getScheme() + "://" + targetBase.getAuthority();
        if (value.startsWith(origin)) {
            return value.substring(origin.length());
        }
        return value;
    }

    /**
     * Writes {@code message} with the given HTTP status, unless the response has
     * already been committed (in which case the client can no longer receive a
     * status change).
     */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
        }
    }
}
