package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlDatabaseEngine implements DatabaseEngine {

    private final MysqlDatabaseRepository mysqlDatabaseRepository;
    private final String mysqlUri;
    private final String issuedHost;
    private final boolean tls;

    public MysqlDatabaseEngine(MysqlDatabaseRepository mysqlDatabaseRepository,
                               @Value("${app.mysql.uri:jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}") String mysqlUri,
                               @Value("${app.mysql.issued-host:}") String issuedHost,
                               @Value("${app.mysql.tls:false}") boolean tls) {
        this.mysqlDatabaseRepository = mysqlDatabaseRepository;
        this.mysqlUri = mysqlUri;
        this.issuedHost = issuedHost;
        this.tls = tls;
    }

    // Legacy constructor for tests with old public-host/public-tls/public-sslmode shape
    public MysqlDatabaseEngine(MysqlDatabaseRepository mysqlDatabaseRepository,
                               String mysqlUri,
                               String publicHost,
                               boolean publicTls,
                               String publicSslmode) {
        this(mysqlDatabaseRepository, mysqlUri, publicHost, publicTls);
    }

    @Override
    public DatabaseEngineType type() {
        return DatabaseEngineType.MYSQL;
    }

    @Override
    public void createUser(String dbName, String userName, String password) {
        mysqlDatabaseRepository.createUser(dbName, userName, password);
    }

    @Override
    public void createDatabase(String dbName, String owner) {
        mysqlDatabaseRepository.createDatabase(dbName);
    }

    @Override
    public void dropDatabase(String dbName) {
        mysqlDatabaseRepository.dropDatabase(dbName);
    }

    @Override
    public void dropUser(String dbName, String userName) {
        mysqlDatabaseRepository.dropUser(dbName, userName);
    }

    @Override
    public void updateUserPassword(String dbName, String userName, String newPassword) {
        mysqlDatabaseRepository.updateUserPassword(dbName, userName, newPassword);
    }

    @Override
    public boolean databaseExists(String dbName) {
        return mysqlDatabaseRepository.databaseExists(dbName);
    }

    @Override
    public List<String> listDatabaseNames() {
        return mysqlDatabaseRepository.listDatabaseNames();
    }

    @Override
    public Map<String, Long> getDatabaseSizes() {
        return mysqlDatabaseRepository.getDatabaseSizes();
    }

    @Override
    public List<String> getUsers(String dbName) {
        return mysqlDatabaseRepository.getUsers(dbName);
    }

    @Override
    public void ping() {
        mysqlDatabaseRepository.ping();
    }

    @Override
    public void grantPrivileges(String dbName, String userName) {
        mysqlDatabaseRepository.grantPrivileges(dbName, userName);
    }

    @Override
    public String buildConnectionString(String userName, String password, String dbName) {
        String host = resolveHost();
        String encodedUser = uriEncode(userName);
        String encodedPass = uriEncode(password);
        String base = "mysql://" + encodedUser + ":" + encodedPass + "@" + host + "/" + dbName;
        if (tls) {
            return base + "?sslMode=REQUIRED";
        }
        return base;
    }

    String resolveHost() {
        if (issuedHost != null && !issuedHost.isBlank()) {
            return issuedHost.contains(":") ? issuedHost : issuedHost + ":9816";
        }
        // Derive from jdbc:mysql://host:port/db?params
        String uri = mysqlUri;
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            return "127.0.0.1:9816";
        }
        int slash = uri.indexOf('/', schemeEnd + 3);
        String hostPort = slash >= 0 ? uri.substring(schemeEnd + 3, slash) : uri.substring(schemeEnd + 3);
        int q = hostPort.indexOf('?');
        if (q >= 0) hostPort = hostPort.substring(0, q);
        return hostPort.isBlank() ? "127.0.0.1:9816" : hostPort;
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
