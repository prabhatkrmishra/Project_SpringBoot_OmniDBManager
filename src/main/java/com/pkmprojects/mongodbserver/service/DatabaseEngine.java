package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;

import java.util.List;
import java.util.Map;

/**
 * Abstraction over a database engine (MongoDB / PostgreSQL).
 * Each engine owns its DDL, user management, and connection-string format.
 */
public interface DatabaseEngine {

    DatabaseEngineType type();

    void createUser(String dbName, String userName, String password);

    void createDatabase(String dbName, String owner);

    void dropDatabase(String dbName);

    void dropUser(String dbName, String userName);

    void updateUserPassword(String dbName, String userName, String newPassword);

    boolean databaseExists(String dbName);

    List<String> listDatabaseNames();

    Map<String, Long> getDatabaseSizes();

    String buildConnectionString(String userName, String password, String dbName);

    List<String> getUsers(String dbName);

    void ping();

    default void grantPrivileges(String dbName, String userName) {}
}
