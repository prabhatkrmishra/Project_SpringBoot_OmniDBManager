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

    /** Whether to inject the configured basic-auth credential on proxied requests. */
    private final boolean injectAuth;

    /**
     * Shared HTTP client used for every proxied request.
     */
    private final HttpClient http;

    /**
     * Builds the proxy filter from the configured mongo-express connection settings.
     *
     * <p>The injected credential defaults to {@code admin}/{@code admin} and is
     * sent to mongo-express on every proxied request, so any authenticated admin
     * of this app is, transitively, whoever that account can reach. Set
     * {@code MONGO_EXPRESS_INJECT_AUTH=false} to stop injecting it. Note this is
     * not the same as Adminer's flag: mongo-express is configured with basic auth
     * upstream, so turning injection off makes it reject every proxied request
     * unless it has been reconfigured without one. The admin session's own
     * Authorization header is still stripped either way.
     *
     * @param baseUrl    base URL of the mongo-express container
     * @param username   mongo-express basic-auth username
     * @param password   mongo-express basic-auth password
     * @param injectAuth whether to send the configured credential upstream
     */
    @org.springframework.beans.factory.annotation.Autowired
    public MongoExpressProxyFilter(@Value("${app.mongo-express.base-url}") String baseUrl,
                                    @Value("${app.mongo-express.username}") String username,
                                    @Value("${app.mongo-express.password}") String password,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false) HttpClient httpClient,
                                    @Value("${app.mongo-express.inject-auth:true}") boolean injectAuth) {
        this.targetBase = URI.create(baseUrl);
        String token = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.authorization = "Basic " + token;
        this.injectAuth = injectAuth;
        this.http = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Skips the proxy unless the request path is under {@value #PROXY_PREFIX}.
     */
    /** Test/diagnostic accessor: is the configured credential injected upstream? */
    public boolean isInjectAuth() {
        return injectAuth;
    }

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

        byte[] body;
        try {
            body = readBoundedBody(request);
        } catch (PayloadTooLargeException tooLarge) {
            log.warn("Refusing oversized proxied request body for {}", request.getRequestURI());
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, tooLarge.getMessage());
            return;
        }
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(60));
            if (injectAuth) {
                builder.header("Authorization", authorization);
            }
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
        // Keep the result under this filter's prefix, the way AdminerProxyFilter
        // and PhpMyAdminProxyFilter already do. Returning a bare path sent the
        // browser out of /mongo-express and into the app's own routes.
        String origin = targetBase.getScheme() + "://" + targetBase.getAuthority();
        if (value.startsWith(origin)) {
            return PROXY_PREFIX + pathOnly(value.substring(origin.length()));
        }
        if (value.startsWith("/")) {
            return PROXY_PREFIX + pathOnly(value);
        }
        return value;
    }

    private static String pathOnly(String path) {
        return path.isEmpty() ? "/" : path;
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

    /** Ceiling on a proxied request body. Room for a UI file import, far below the heap. */
    static final int MAX_PROXY_BODY_BYTES = 64 * 1024 * 1024;

    /**
     * Reads the request body, refusing anything past {@link #MAX_PROXY_BODY_BYTES}.
     *
     * <p>{@code spring.servlet.multipart.max-request-size} does not cover this: it
     * governs multipart only, and proxied UI requests arrive as
     * {@code application/octet-stream}. All three proxy prefixes are also
     * CSRF-exempt, so without a bound a handful of concurrent large posts is enough
     * to exhaust a 256 MB heap. These routes are ADMIN-only, which makes this an
     * authenticated self-DoS rather than a remote one -- still worth refusing
     * cleanly instead of dying.
     *
     * @throws PayloadTooLargeException when the body exceeds the limit
     */
    static byte[] readBoundedBody(jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_PROXY_BODY_BYTES + 1);
        if (body.length > MAX_PROXY_BODY_BYTES) {
            throw new PayloadTooLargeException();
        }
        return body;
    }

    /** Request body exceeded the proxy limit. */
    static final class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException() {
            super("proxied request body exceeds " + MAX_PROXY_BODY_BYTES + " bytes");
        }
    }
}
