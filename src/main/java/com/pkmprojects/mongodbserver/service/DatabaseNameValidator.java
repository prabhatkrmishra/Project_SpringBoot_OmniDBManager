package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Validates database/collection/user names per engine.
 * Mongo: [A-Za-z0-9_-]+ max 64. Postgres: ^[a-z_][a-z0-9_]*$ max 63, lowercased.
 */
@Component
@Primary
public class DatabaseNameValidator {

    static final Set<String> SYSTEM_DATABASES = Set.of("admin", "local", "config", "mongodb_admin");
    static final Set<String> POSTGRES_SYSTEM_DATABASES = Set.of("postgres", "template0", "template1");
    static final Set<String> MYSQL_SYSTEM_DATABASES = Set.of("information_schema", "mysql", "performance_schema", "sys");

    private static final int MAX_MONGO_NAME_LENGTH = 64;
    private static final int MAX_POSTGRES_NAME_LENGTH = 63;
    private static final int MAX_MYSQL_NAME_LENGTH = 64;
    private static final int MAX_MYSQL_USER_LENGTH = 32;

    private static final String MONGO_DATABASE_PATTERN = "[A-Za-z0-9_-]+";
    private static final String MONGO_USER_PATTERN = "[A-Za-z0-9_.-]+";
    private static final String MONGO_COLLECTION_PATTERN = "[A-Za-z0-9_-]+";
    private static final String POSTGRES_PATTERN = "[a-z_][a-z0-9_]*";
    private static final String MYSQL_PATTERN = "[a-z_][a-z0-9_]*";

    public void validateDatabaseName(String dbName) {
        validateDatabaseName(dbName, DatabaseEngineType.MONGO);
    }

    public void validateDatabaseName(String dbName, DatabaseEngineType engine) {
        if (engine == DatabaseEngineType.POSTGRES) {
            validatePostgresDatabaseName(dbName);
        } else if (engine == DatabaseEngineType.MYSQL) {
            validateMysqlDatabaseName(dbName);
        } else {
            validateMongoDatabaseName(dbName);
        }
    }

    public void validateMongoDatabaseName(String dbName) {
        requireValid(dbName, MONGO_DATABASE_PATTERN, MAX_MONGO_NAME_LENGTH,
                "Database name may only contain letters, digits, '_' and '-'");
        String lower = dbName.toLowerCase(Locale.ROOT);
        if (SYSTEM_DATABASES.contains(lower)) {
            throw new NameNotAllowedException("Database '" + dbName + "' is a system database and cannot be managed");
        }
    }

    public void validatePostgresDatabaseName(String dbName) {
        requireValid(dbName, POSTGRES_PATTERN, MAX_POSTGRES_NAME_LENGTH,
                "Postgres database name must start with a letter or underscore and contain only lowercase letters, digits, and underscores");
        String lower = dbName.toLowerCase(Locale.ROOT);
        if (POSTGRES_SYSTEM_DATABASES.contains(lower)) {
            throw new NameNotAllowedException("Database '" + dbName + "' is a system database and cannot be managed");
        }
        if (!dbName.equals(lower)) {
            throw new NameNotAllowedException("Postgres database name must be lowercase");
        }
    }

    public void validateUserName(String userName) {
        validateUserName(userName, DatabaseEngineType.MONGO);
    }

    public void validateUserName(String userName, DatabaseEngineType engine) {
        if (engine == DatabaseEngineType.POSTGRES) {
            validatePostgresUserName(userName);
        } else if (engine == DatabaseEngineType.MYSQL) {
            validateMysqlUserName(userName);
        } else {
            requireValid(userName, MONGO_USER_PATTERN, MAX_MONGO_NAME_LENGTH,
                    "Database user name may only contain letters, digits, '.', '_' and '-'");
        }
    }

    public void validatePostgresUserName(String userName) {
        requireValid(userName, POSTGRES_PATTERN, MAX_POSTGRES_NAME_LENGTH,
                "Postgres user name must start with a letter or underscore and contain only lowercase letters, digits, and underscores");
        if (!userName.equals(userName.toLowerCase(Locale.ROOT))) {
            throw new NameNotAllowedException("Postgres user name must be lowercase");
        }
    }

    public void validateCollectionName(String collectionName) {
        requireValid(collectionName, MONGO_COLLECTION_PATTERN, MAX_MONGO_NAME_LENGTH,
                "Collection name may only contain letters, digits, '_' and '-'");
    }

    public void validateTableName(String tableName) {
        requireValid(tableName, POSTGRES_PATTERN, MAX_POSTGRES_NAME_LENGTH,
                "Table name must start with a letter or underscore and contain only lowercase letters, digits, and underscores");
        if (!tableName.equals(tableName.toLowerCase(Locale.ROOT))) {
            throw new NameNotAllowedException("Table name must be lowercase");
        }
    }

    public void validateMysqlDatabaseName(String dbName) {
        requireValid(dbName, MYSQL_PATTERN, MAX_MYSQL_NAME_LENGTH,
                "MySQL database name must start with a letter or underscore and contain only lowercase letters, digits, and underscores");
        String lower = dbName.toLowerCase(Locale.ROOT);
        if (MYSQL_SYSTEM_DATABASES.contains(lower)) {
            throw new NameNotAllowedException("Database '" + dbName + "' is a system database and cannot be managed");
        }
        if (!dbName.equals(lower)) {
            throw new NameNotAllowedException("MySQL database name must be lowercase");
        }
    }

    public void validateMysqlUserName(String userName) {
        requireValid(userName, MYSQL_PATTERN, MAX_MYSQL_USER_LENGTH,
                "MySQL user name must start with a letter or underscore and contain only lowercase letters, digits, and underscores");
        if (!userName.equals(userName.toLowerCase(Locale.ROOT))) {
            throw new NameNotAllowedException("MySQL user name must be lowercase");
        }
    }

    public void validateMysqlTableName(String tableName) {
        requireValid(tableName, MYSQL_PATTERN, MAX_MYSQL_NAME_LENGTH,
                "Table name must start with a letter or underscore and contain only lowercase letters, digits, and underscores");
        if (!tableName.equals(tableName.toLowerCase(Locale.ROOT))) {
            throw new NameNotAllowedException("Table name must be lowercase");
        }
    }

    public void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            return;
        }
        if (password.length() < 8) {
            throw new NameNotAllowedException("Password must be at least 8 characters");
        }
        if (password.length() > 128) {
            throw new NameNotAllowedException("Password must be at most 128 characters");
        }
    }

    private void requireValid(String value, String pattern, int maxLen, String message) {
        if (value == null || value.isBlank()) {
            throw new NameNotAllowedException("A name is required");
        }
        if (value.length() > maxLen) {
            throw new NameNotAllowedException("Name must be at most " + maxLen + " characters");
        }
        if (!value.matches(pattern)) {
            throw new NameNotAllowedException(message);
        }
    }
}
