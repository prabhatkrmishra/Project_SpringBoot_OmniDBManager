package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MongoDatabaseEngine implements DatabaseEngine {

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final Environment environment;

    public MongoDatabaseEngine(MongoDatabaseRepository mongoDatabaseRepository, Environment environment) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.environment = environment;
    }

    @Override
    public DatabaseEngineType type() {
        return DatabaseEngineType.MONGO;
    }

    @Override
    public void createUser(String dbName, String userName, String password) {
        mongoDatabaseRepository.createUser(dbName, userName, password);
    }

    @Override
    public void createDatabase(String dbName, String owner) {
        mongoDatabaseRepository.createDatabase(dbName);
    }

    @Override
    public void dropDatabase(String dbName) {
        mongoDatabaseRepository.dropDatabase(dbName);
    }

    @Override
    public void dropUser(String dbName, String userName) {
        mongoDatabaseRepository.dropUser(dbName, userName);
    }

    @Override
    public void updateUserPassword(String dbName, String userName, String newPassword) {
        mongoDatabaseRepository.updateUserPassword(dbName, userName, newPassword);
    }

    @Override
    public boolean databaseExists(String dbName) {
        return mongoDatabaseRepository.databaseExists(dbName);
    }

    @Override
    public List<String> listDatabaseNames() {
        return mongoDatabaseRepository.listDatabaseNames();
    }

    @Override
    public Map<String, Long> getDatabaseSizes() {
        return mongoDatabaseRepository.getDatabaseSizes();
    }

    @Override
    public List<String> getUsers(String dbName) {
        return mongoDatabaseRepository.getUsers(dbName).stream()
                .map(doc -> doc.getString("user"))
                .collect(Collectors.toList());
    }

    @Override
    public void ping() {
        mongoDatabaseRepository.ping();
    }

    @Override
    public String buildConnectionString(String userName, String password, String dbName) {
        return "mongodb://" + uriEncode(userName) + ":" + uriEncode(password) + "@" + resolveConnectionHost() + "/" + dbName
                + "?authSource=" + dbName + (resolveConnectionTls() ? "&tls=true" : "");
    }

    String resolveConnectionHost() {
        String publicHost = environment.getProperty("app.mongo-public-host", "");
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost;
        }
        String uri = environment.getProperty("spring.mongodb.uri", "");
        if (uri == null || uri.isBlank()) {
            return "127.0.0.1:9812";
        }
        int at = uri.lastIndexOf('@');
        if (at < 0) {
            return "127.0.0.1:9812";
        }
        String rest = uri.substring(at + 1);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private boolean resolveConnectionTls() {
        Boolean tls = environment.getProperty("app.mongo-public-tls", Boolean.class, false);
        return Boolean.TRUE.equals(tls);
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
