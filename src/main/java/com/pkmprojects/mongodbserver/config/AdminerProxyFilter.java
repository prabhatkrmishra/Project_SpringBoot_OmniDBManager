package com.pkmprojects.mongodbserver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reverse proxy exposing the bundled Adminer UI through the admin app, with
 * single sign-on as the Postgres superuser so one click from the dashboard
 * sees every provisioned database — no second login form.
 *
 * <p>How it works: Adminer keeps its login in two cookies ({@code adminer_sid}
 * and {@code adminer_key}). On the first page load without them, this filter
 * logs in upstream server-side (GET the login page for its {@code token},
 * POST {@code auth[driver]=pgsql}, {@code auth[server]=postgres} with the
 * root credentials) and hands the resulting cookies to the browser scoped to
 * {@code /adminer}. Later requests forward only those two cookies upstream.
 * The Spring admin session cookie is never forwarded, and the password never
 * appears in a URL, a log line, or the browser — only in the server-side POST
 * body. Any SSO failure falls back to proxying the plain login page.</p>
 */
@Component
public class AdminerProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminerProxyFilter.class);
    private static final String PROXY_PREFIX = "/adminer";
    private static final Set<String> NON_FORWARDED_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    /**
     * The only cookies ever forwarded to (or accepted from) Adminer. Verified
     * against {@code adminer:6.0.1-standalone}: these two are the whole session.
     */
    static final Set<String> ADMINER_COOKIES = Set.of("adminer_sid", "adminer_key");

    private static final String ADMINER_SERVER = "postgres";
    private static final String ADMINER_DRIVER = "pgsql";

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("name=['\"]token['\"]\\s+value=['\"]([^'\"]+)['\"]");
    private static final Pattern SET_COOKIE_PATH_PATTERN =
            Pattern.compile("(?i)(;\\s*path\\s*=\\s*)/(\\s*;|\\s*$)");

    private final URI targetBase;
    private final HttpClient http;
    private final String adminerUsername;
    private final String adminerPassword;

    public AdminerProxyFilter(@Value("${app.adminer.base-url:http://127.0.0.1:9815}") String baseUrl,
                              @Value("${POSTGRES_ROOT_USER:root}") String adminerUsername,
                              @Value("${POSTGRES_ROOT_PASSWORD:root}") String adminerPassword,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) HttpClient httpClient) {
        this.targetBase = URI.create(baseUrl);
        this.adminerUsername = adminerUsername;
        this.adminerPassword = adminerPassword;
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
        String outboundCookie = adminerCookieHeader(request.getCookies());
        if (outboundCookie == null && "GET".equalsIgnoreCase(request.getMethod())
                && (path.equals("/") || path.isEmpty())) {
            List<String> ssoCookies = tryServerSideLogin();
            if (ssoCookies != null && !ssoCookies.isEmpty()) {
                ssoCookies.forEach(value -> response.addHeader("Set-Cookie", value));
                outboundCookie = cookieHeaderFromSetCookies(ssoCookies);
            }
        }
        if (outboundCookie != null) {
            builder.header("Cookie", outboundCookie);
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
                    if (lower.equals("location")) {
                        response.addHeader(name, rewriteLocation(value));
                    } else if (lower.equals("set-cookie")) {
                        String rewritten = rewriteSetCookie(value);
                        if (rewritten != null) {
                            response.addHeader(name, rewritten);
                        }
                    } else {
                        response.addHeader(name, value);
                    }
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

    /**
     * Logs in to Adminer upstream as the Postgres superuser and returns the
     * session cookies (with path rewritten to {@code /adminer}), or
     * {@code null} when anything goes wrong so the caller falls back to the
     * plain proxied login page. Never throws, never logs credentials.
     */
    private List<String> tryServerSideLogin() {
        try {
            HttpRequest loginPage = HttpRequest.newBuilder(URI.create(targetBase + "/"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> page = http.send(loginPage, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (page.statusCode() != 200) {
                return null;
            }
            String token = parseLoginToken(page.body());
            if (token == null) {
                return null;
            }
            Map<String, String> initialCookies = setCookiesToMap(
                    page.headers().allValues("Set-Cookie"));
            String form = "auth%5Bdriver%5D=" + encode(ADMINER_DRIVER)
                    + "&auth%5Bserver%5D=" + encode(ADMINER_SERVER)
                    + "&auth%5Busername%5D=" + encode(adminerUsername)
                    + "&auth%5Bpassword%5D=" + encode(adminerPassword)
                    + "&auth%5Bdb%5D="
                    + "&token=" + encode(token);
            HttpRequest.Builder loginBuilder = HttpRequest.newBuilder(URI.create(targetBase + "/"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
            String cookieHeader = cookieHeaderFromMap(initialCookies);
            if (cookieHeader != null) {
                loginBuilder.header("Cookie", cookieHeader);
            }
            HttpResponse<byte[]> login = http.send(loginBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = login.statusCode();
            boolean redirect = status == 301 || status == 302 || status == 303
                    || status == 307 || status == 308;
            if (!redirect) {
                return null;
            }
            Map<String, String> session = new LinkedHashMap<>(initialCookies);
            session.putAll(setCookiesToMap(login.headers().allValues("Set-Cookie")));
            session.keySet().retainAll(ADMINER_COOKIES);
            if (!session.containsKey("adminer_sid")) {
                return null;
            }
            List<String> rewritten = new ArrayList<>();
            session.forEach((name, value) ->
                    rewritten.add(rewriteSetCookie(name + "=" + value + "; path=/; HttpOnly")));
            return rewritten;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Adminer single sign-on was interrupted");
            return null;
        } catch (Exception e) {
            log.debug("Adminer single sign-on unavailable, falling back to login page: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds the outbound {@code Cookie} header from the browser's cookies,
     * keeping only the Adminer session pair. Returns {@code null} when the
     * request carries no Adminer session.
     */
    static String adminerCookieHeader(Cookie[] cookies) {
        if (cookies == null || cookies.length == 0) {
            return null;
        }
        StringBuilder header = new StringBuilder();
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.getName() == null) continue;
            if (!ADMINER_COOKIES.contains(cookie.getName())) continue;
            if (cookie.getValue() == null || cookie.getValue().isBlank()) continue;
            if (header.length() > 0) header.append("; ");
            header.append(cookie.getName()).append('=').append(cookie.getValue());
        }
        return header.length() == 0 ? null : header.toString();
    }

    /**
     * Rewrites an upstream {@code Set-Cookie} to scope it under
     * {@code /adminer}. Returns {@code null} for cookies that do not belong
     * to Adminer so callers can drop them.
     */
    static String rewriteSetCookie(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return null;
        int equals = headerValue.indexOf('=');
        if (equals <= 0) return null;
        String name = headerValue.substring(0, equals).trim();
        if (!ADMINER_COOKIES.contains(name)) return null;
        return rewriteSetCookiePath(headerValue);
    }

    static String rewriteSetCookiePath(String headerValue) {
        Matcher matcher = SET_COOKIE_PATH_PATTERN.matcher(headerValue);
        if (matcher.find()) {
            return matcher.replaceFirst("$1/adminer$2");
        }
        return headerValue + "; Path=/adminer";
    }

    static String cookieHeaderFromSetCookies(List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) return null;
        return cookieHeaderFromMap(setCookiesToMap(setCookieHeaders));
    }

    static Map<String, String> setCookiesToMap(List<String> setCookieHeaders) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (setCookieHeaders == null) return cookies;
        for (String header : setCookieHeaders) {
            if (header == null) continue;
            int equals = header.indexOf('=');
            if (equals <= 0) continue;
            String name = header.substring(0, equals).trim();
            if (!ADMINER_COOKIES.contains(name)) continue;
            String rest = header.substring(equals + 1);
            int end = rest.indexOf(';');
            String value = (end < 0 ? rest : rest.substring(0, end)).trim();
            if (!value.isEmpty()) {
                cookies.put(name, value);
            }
        }
        return cookies;
    }

    static String cookieHeaderFromMap(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return null;
        StringBuilder header = new StringBuilder();
        cookies.forEach((name, value) -> {
            if (header.length() > 0) header.append("; ");
            header.append(name).append('=').append(value);
        });
        return header.length() == 0 ? null : header.toString();
    }

    static String parseLoginToken(String html) {
        if (html == null || html.isBlank()) return null;
        Matcher matcher = TOKEN_PATTERN.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
}
