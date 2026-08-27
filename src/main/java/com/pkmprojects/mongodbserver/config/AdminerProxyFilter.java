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
public class AdminerProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminerProxyFilter.class);
    private static final String PROXY_PREFIX = "/adminer";
    private static final Set<String> NON_FORWARDED_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final URI targetBase;
    private final HttpClient http;

    public AdminerProxyFilter(@Value("${app.adminer.base-url:http://127.0.0.1:9815}") String baseUrl) {
        this.targetBase = URI.create(baseUrl);
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
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

        byte[] body = request.getInputStream().readAllBytes();
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(60));
        } catch (IllegalArgumentException e) {
            log.debug("Cannot proxy request with unparseable target '{}'", target);
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "The requested path cannot be proxied to Adminer");
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
            log.warn("Adminer proxy request was interrupted", e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "Adminer proxy request was interrupted");
        } catch (ConnectException e) {
            log.warn("Adminer is not reachable at {}", targetBase, e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "Adminer is not reachable. Is the container running?");
        } catch (IOException e) {
            log.error("Adminer proxy request to {} failed", target, e);
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "Adminer proxy request failed");
        }
    }

    private String rewriteLocation(String value) {
        if (value == null) return value;
        String origin = targetBase.getScheme() + "://" + targetBase.getAuthority();
        if (value.startsWith(origin)) return value.substring(origin.length());
        return value;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
        }
    }
}
