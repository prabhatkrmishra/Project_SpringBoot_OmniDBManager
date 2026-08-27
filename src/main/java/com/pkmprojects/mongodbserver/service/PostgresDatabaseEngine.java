package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresDatabaseEngine implements DatabaseEngine {

    private final PostgresDatabaseRepository postgresDatabaseRepository;
    private final Environment environment;
    private final String postgresUri;
    private final String publicHost;
    private final boolean publicTls;
    private final String publicSslmode;

    public PostgresDatabaseEngine(PostgresDatabaseRepository postgresDatabaseRepository,
                                  Environment environment,
                                  @Value("${app.postgres.uri:jdbc:postgresql://127.0.0.1:9813/postgres}") String postgresUri,
                                  @Value("${app.postgres.public-host:}") String publicHost,
                                  @Value("${app.postgres.public-tls:false}") boolean publicTls,
                                  @Value("${app.postgres.public-sslmode:require}") String publicSslmode) {
        this.postgresDatabaseRepository = postgresDatabaseRepository;
        this.environment = environment;
        this.postgresUri = postgresUri;
        this.publicHost = publicHost;
        this.publicTls = publicTls;
        this.publicSslmode = publicSslmode;
    }

    @Override
    public DatabaseEngineType type() {
        return DatabaseEngineType.POSTGRES;
    }

    @Override
    public void createUser(String dbName, String userName, String password) {
        postgresDatabaseRepository.createUser(dbName, userName, password);
    }

    @Override
    public void createDatabase(String dbName, String owner) {
        postgresDatabaseRepository.createDatabase(dbName, owner);
    }

    @Override
    public void dropDatabase(String dbName) {
        postgresDatabaseRepository.dropDatabase(dbName);
    }

    @Override
    public void dropUser(String dbName, String userName) {
        postgresDatabaseRepository.dropUser(dbName, userName);
    }

    @Override
    public void updateUserPassword(String dbName, String userName, String newPassword) {
        postgresDatabaseRepository.updateUserPassword(dbName, userName, newPassword);
    }

    @Override
    public boolean databaseExists(String dbName) {
        return postgresDatabaseRepository.databaseExists(dbName);
    }

    @Override
    public List<String> listDatabaseNames() {
        return postgresDatabaseRepository.listDatabaseNames();
    }

    @Override
    public Map<String, Long> getDatabaseSizes() {
        return postgresDatabaseRepository.getDatabaseSizes();
    }

    @Override
    public List<String> getUsers(String dbName) {
        return postgresDatabaseRepository.getUsers(dbName);
    }

    @Override
    public void ping() {
        postgresDatabaseRepository.ping();
    }

    @Override
    public void grantPrivileges(String dbName, String userName) {
        postgresDatabaseRepository.grantPrivileges(dbName, userName);
    }

    @Override
    public String buildConnectionString(String userName, String password, String dbName) {
        String host = resolveHost();
        String encodedUser = uriEncode(userName);
        String encodedPass = uriEncode(password);
        String base = "postgresql://" + encodedUser + ":" + encodedPass + "@" + host + "/" + dbName;
        String sslmode = publicTls ? publicSslmode : "disable";
        // Only add sslmode when TLS is enabled or explicitly require; keep disable explicit for clarity
        if (publicTls) {
            return base + "?sslmode=" + sslmode + "&application_name=omnidb";
        }
        return base + "?application_name=omnidb";
    }

    String resolveHost() {
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost;
        }
        // Derive from jdbc:postgresql://host:port/db
        String uri = postgresUri;
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            return "127.0.0.1:9813";
        }
        int slash = uri.indexOf('/', schemeEnd + 3);
        String hostPort = slash >= 0 ? uri.substring(schemeEnd + 3, slash) : uri.substring(schemeEnd + 3);
        int q = hostPort.indexOf('?');
        if (q >= 0) hostPort = hostPort.substring(0, q);
        return hostPort.isBlank() ? "127.0.0.1:9813" : hostPort;
    }

    static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '.' || b == '_' || b == '~') {
                encoded.append((char) b);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return encoded.toString();
    }
}
