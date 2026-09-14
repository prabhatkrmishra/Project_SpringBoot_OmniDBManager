package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Provision-time tenant-login proof for MySQL and MongoDB.
 *
 * <p>PostgreSQL already validates end-to-end at provision time
 * ({@link ConnectionValidationService}); MySQL/Mongo branches created the
 * user + database with no login proof, so a typo'd or wrongly-granted
 * credential surfaced only at first app use. This service closes that gap
 * with the same contract:</p>
 * <ul>
 *   <li>connect <b>as the tenant</b> (never root) with the just-created
 *       password;</li>
 *   <li>touch the <b>target database</b> with a minimal read
 *       ({@code SELECT 1} / {@code ping}) — proves user exists,
 *       authentication succeeds, and the target DB is accessible;</li>
 *   <li>mutate nothing (no writes, no DDL);</li>
 *   <li>fail closed ({@code false}) on bad password, wrong user, wrong DB,
 *       missing privileges, or engine outage — the caller drops the
 *       half-created resources via its existing cleanup arms.</li>
 * </ul>
 *
 * <p>Connections are short-lived (created + closed per validation, 5s
 * timeouts) so no pool or lifecycle is introduced. Absent = previous
 * behavior: {@link ProvisioningService} skips validation when no validator
 * is wired (unit tests, engine-disabled installs).</p>
 */
@Service
public class TenantLoginValidationService {

    private static final Logger log = LoggerFactory.getLogger(TenantLoginValidationService.class);

    private final Environment environment;
    private final String mysqlUri;
    private final boolean mysqlEnabled;
    private final boolean mongoEnabled;

    @Autowired
    public TenantLoginValidationService(Environment environment,
                                        @Value("${app.mysql.uri:jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}") String mysqlUri,
                                        @Value("${app.mysql.enabled:false}") boolean mysqlEnabled,
                                        @Value("${app.mongo.enabled:false}") boolean mongoEnabled) {
        this.environment = environment;
        this.mysqlUri = mysqlUri;
        this.mysqlEnabled = mysqlEnabled;
        this.mongoEnabled = mongoEnabled;
    }

    /**
     * Proves the MySQL tenant login: {@code SELECT 1} as
     * {@code user/password} against the target database.
     */
    public boolean validateMysql(String dbName, String user, String password) {
        if (!mysqlEnabled) return false;
        String jdbc = tenantMysqlJdbc(dbName);
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "5000");
        try (var c = DriverManager.getConnection(jdbc, props);
             var st = c.createStatement()) {
            st.setQueryTimeout(5);
            try (var rs = st.executeQuery("SELECT 1")) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            log.debug("TenantLoginValidationService mysql validation failed for db={} user={}", dbName, user, e);
            return false;
        }
    }

    /**
     * Proves the MongoDB tenant login: {@code ping} as
     * {@code user/password} against the target database.
     */
    public boolean validateMongo(String dbName, String user, String password) {
        if (!mongoEnabled) return false;
        String uri = tenantMongoUri(dbName, user, password);
        if (uri == null) return false;
        try (MongoClient client = MongoClients.create(uri)) {
            Document pong = client.getDatabase(dbName).runCommand(new Document("ping", 1));
            if (pong == null) return false;
            Object ok = pong.get("ok");
            return ok instanceof Number number && Double.compare(number.doubleValue(), 1.0) == 0;
        } catch (MongoException e) {
            log.debug("TenantLoginValidationService mongo validation failed for db={} user={}", dbName, user, e);
            return false;
        } catch (RuntimeException e) {
            log.debug("TenantLoginValidationService mongo validation failed for db={} user={}", dbName, user, e);
            return false;
        }
    }

    /**
     * Derives the tenant JDBC URL from the root URI shape: same host/port +
     * params, catalog swapped to the new database. Tenant credentials travel
     * as connection properties, never in the URL.
     */
    String tenantMysqlJdbc(String dbName) {
        String uri = mysqlUri != null ? mysqlUri : "jdbc:mysql://127.0.0.1:9816/mysql";
        int schemeEnd = uri.indexOf("://");
        String afterScheme = schemeEnd >= 0 ? uri.substring(schemeEnd + 3) : uri;
        int slash = afterScheme.indexOf('/');
        String hostPort = slash >= 0 ? afterScheme.substring(0, slash) : afterScheme;
        String params = "";
        if (slash >= 0) {
            String rest = afterScheme.substring(slash + 1);
            int q = rest.indexOf('?');
            if (q >= 0) params = rest.substring(q);
        }
        if (hostPort.isBlank()) hostPort = "127.0.0.1:9816";
        return "jdbc:mysql://" + hostPort + "/" + dbName + params;
    }

    /**
     * Builds a short-lived tenant Mongo URI from the root URI shape: same
     * hosts + options, credentials swapped to the tenant, authSource pinned
     * to the new database. Returns {@code null} when the root URI shape is
     * unparseable (fail closed — caller treats as validation failure).
     */
    String tenantMongoUri(String dbName, String user, String password) {
        String root = environment.getProperty("spring.mongodb.uri", "");
        if (root == null || root.isBlank() || !root.startsWith("mongodb")) return null;
        int schemeEnd = root.indexOf("://");
        if (schemeEnd < 0) return null;
        String afterScheme = root.substring(schemeEnd + 3);
        int at = afterScheme.lastIndexOf('@');
        String hostsAndRest = at >= 0 ? afterScheme.substring(at + 1) : afterScheme;
        if (hostsAndRest.isBlank()) return null;
        String encodedUser = URLEncoder.encode(user, StandardCharsets.UTF_8);
        String encodedPass = URLEncoder.encode(password, StandardCharsets.UTF_8);
        String base = root.substring(0, schemeEnd + 3) + encodedUser + ":" + encodedPass + "@" + hostsAndRest;
        // Pin authSource to the tenant DB: strip any existing authSource
        // param, then append ours. Other options (tls, timeouts) are kept.
        int q = base.indexOf('?');
        String head = q >= 0 ? base.substring(0, q) : base;
        String query = q >= 0 ? base.substring(q + 1) : "";
        StringBuilder kept = new StringBuilder();
        if (!query.isBlank()) {
            for (String param : query.split("&")) {
                if (param.equalsIgnoreCase("authSource") || param.toLowerCase().startsWith("authsource=")) continue;
                if (!kept.isEmpty()) kept.append('&');
                kept.append(param);
            }
        }
        int slash = head.lastIndexOf('/');
        // head ends with /<db?> — replace the path with the tenant DB.
        String headNoDb = slash >= 3 ? head.substring(0, slash) : head;
        String rebuilt = headNoDb + "/" + dbName;
        if (!kept.isEmpty()) rebuilt += "?" + kept + "&authSource=" + dbName;
        else rebuilt += "?authSource=" + dbName;
        return rebuilt;
    }
}
