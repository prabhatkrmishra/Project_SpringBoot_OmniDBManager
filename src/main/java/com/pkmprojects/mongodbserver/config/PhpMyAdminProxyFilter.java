package com.pkmprojects.mongodbserver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

@Component
public class PhpMyAdminProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PhpMyAdminProxyFilter.class);
    private static final String PROXY_PREFIX = "/phpmyadmin";
    private static final Set<String> NON_FORWARDED_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final URI targetBase;
    private final HttpClient http;

    public PhpMyAdminProxyFilter(@Value("${app.phpmyadmin.base-url:http://127.0.0.1:9817}") String baseUrl,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) HttpClient httpClient) {
        this.targetBase = URI.create(baseUrl);
        this.http = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROXY_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI().substring(PROXY_PREFIX.length());
        if (path.isEmpty()) path = "/";
        String target = targetBase + path;
        if (request.getQueryString() != null) target += "?" + request.getQueryString();

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
        } catch (IllegalArgumentException e) {
            log.debug("Cannot proxy request with unparseable target '{}'", target);
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "The requested path cannot be proxied to phpMyAdmin");
            return;
        }

        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (NON_FORWARDED_HEADERS.contains(name.toLowerCase())) continue;
            if (name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("cookie")) continue;
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
                if (NON_FORWARDED_HEADERS.contains(lower)) return;
                for (String value : values) {
                    response.addHeader(name, lower.equals("location") ? rewriteLocation(value) : value);
                }
            });
            byte[] upstreamBody = upstream.body();
            response.setContentLength(upstreamBody.length);
            response.getOutputStream().write(upstreamBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("phpMyAdmin proxy request was interrupted", e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "phpMyAdmin proxy request was interrupted");
        } catch (ConnectException e) {
            log.warn("phpMyAdmin is not reachable at {}", targetBase, e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "phpMyAdmin is not reachable. Is the container running?");
        } catch (IOException e) {
            log.error("phpMyAdmin proxy request to {} failed", target, e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "phpMyAdmin proxy request failed");
        }
    }

    private String rewriteLocation(String value) {
        if (value == null) return value;
        String origin = targetBase.getScheme() + "://" + targetBase.getAuthority();
        if (value.startsWith(origin)) {
            String path = value.substring(origin.length());
            if (path.isEmpty()) path = "/";
            return PROXY_PREFIX + (path.startsWith("/") ? path : "/" + path);
        }
        if (value.startsWith("/")) {
            return PROXY_PREFIX + value;
        }
        return value;
    }

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
