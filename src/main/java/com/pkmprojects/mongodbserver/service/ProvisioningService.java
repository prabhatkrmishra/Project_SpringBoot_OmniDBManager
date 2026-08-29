package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.DatabaseUser;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.store.AuditStore;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import com.pkmprojects.mongodbserver.security.PasswordGenerator;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private static final int GENERATED_PASSWORD_LENGTH = 16;
    private static final int MONGO_CODE_USER_NOT_FOUND = 11;
    private static final int MONGO_CODE_NAMESPACE_NOT_FOUND = 26;
    private static final int MONGO_CODE_USER_ALREADY_EXISTS = 51003;

    private final Optional<MongoDatabaseRepository> mongoDatabaseRepository;
    private final ManagedDatabaseStore managedDatabaseStore;
    private final AuditStore auditStore;
    private final DatabaseNameValidator nameValidator;
    private final PasswordGenerator passwordGenerator;
    private final java.time.Clock clock;
    private final Environment environment;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DatabaseLockRegistry databaseLocks;
    private final Optional<MongoDatabaseEngine> mongoEngine;
    private final Optional<PostgresDatabaseEngine> postgresEngine;
    private final Optional<PostgresDatabaseRepository> postgresRepository;
    private final Optional<MysqlDatabaseEngine> mysqlEngine;
    private final Optional<MysqlDatabaseRepository> mysqlRepository;
    private final EncryptionService encryptionService;

    @Autowired
    public ProvisioningService(@Autowired(required = false) MongoDatabaseRepository mongoDatabaseRepository,
                               ManagedDatabaseStore managedDatabaseStore,
                               AuditStore auditStore,
                               DatabaseNameValidator nameValidator,
                               PasswordGenerator passwordGenerator,
                               java.time.Clock clock,
                               Environment environment,
                               ApplicationEventPublisher applicationEventPublisher,
                               DatabaseLockRegistry databaseLocks,
                               @Autowired(required = false) MongoDatabaseEngine mongoEngine,
                               @Autowired(required = false) PostgresDatabaseEngine postgresEngine,
                               @Autowired(required = false) PostgresDatabaseRepository postgresRepository,
                               @Autowired(required = false) MysqlDatabaseEngine mysqlEngine,
                               @Autowired(required = false) MysqlDatabaseRepository mysqlRepository,
                               @Autowired(required = false) EncryptionService encryptionService) {
        this.mongoDatabaseRepository = Optional.ofNullable(mongoDatabaseRepository);
        this.managedDatabaseStore = managedDatabaseStore;
        this.auditStore = auditStore;
        this.nameValidator = nameValidator;
        this.passwordGenerator = passwordGenerator;
        this.clock = clock;
        this.environment = environment;
        this.applicationEventPublisher = applicationEventPublisher;
        this.databaseLocks = databaseLocks;
        this.mongoEngine = Optional.ofNullable(mongoEngine);
        this.postgresEngine = Optional.ofNullable(postgresEngine);
        this.postgresRepository = Optional.ofNullable(postgresRepository);
        this.mysqlEngine = Optional.ofNullable(mysqlEngine);
        this.mysqlRepository = Optional.ofNullable(mysqlRepository);
        this.encryptionService = encryptionService;
    }

    // Legacy 12-arg constructor for tests without EncryptionService
    public ProvisioningService(MongoDatabaseRepository mongoDatabaseRepository,
                               ManagedDatabaseStore managedDatabaseStore,
                               AuditLogRepository auditLogRepository,
                               DatabaseNameValidator nameValidator,
                               PasswordGenerator passwordGenerator,
                               java.time.Clock clock,
                               Environment environment,
                               ApplicationEventPublisher applicationEventPublisher,
                               DatabaseLockRegistry databaseLocks,
                               MongoDatabaseEngine mongoEngine,
                               PostgresDatabaseEngine postgresEngine,
                               PostgresDatabaseRepository postgresRepository) {
        this(mongoDatabaseRepository, managedDatabaseStore,
                new com.pkmprojects.mongodbserver.store.AuditLogRepositoryAdapter(auditLogRepository), nameValidator,
                passwordGenerator, clock, environment, applicationEventPublisher, databaseLocks,
                mongoEngine, postgresEngine, postgresRepository, null, null, null);
    }

    // Legacy 13-arg constructor for tests without MySQL (keeps existing tests green)
    public ProvisioningService(MongoDatabaseRepository mongoDatabaseRepository,
                               ManagedDatabaseStore managedDatabaseStore,
                               AuditLogRepository auditLogRepository,
                               DatabaseNameValidator nameValidator,
                               PasswordGenerator passwordGenerator,
                               java.time.Clock clock,
                               Environment environment,
                               ApplicationEventPublisher applicationEventPublisher,
                               DatabaseLockRegistry databaseLocks,
                               MongoDatabaseEngine mongoEngine,
                               PostgresDatabaseEngine postgresEngine,
                               PostgresDatabaseRepository postgresRepository,
                               EncryptionService encryptionService) {
        this(mongoDatabaseRepository, managedDatabaseStore,
                new com.pkmprojects.mongodbserver.store.AuditLogRepositoryAdapter(auditLogRepository), nameValidator,
                passwordGenerator, clock, environment, applicationEventPublisher, databaseLocks,
                mongoEngine, postgresEngine, postgresRepository, null, null, encryptionService);
    }

    private String encryptPassword(String password) {
        if (encryptionService == null) return password;
        return encryptionService.encrypt(password);
    }

    private String decryptPassword(String stored) {
        if (stored == null) return null;
        if (encryptionService == null) return stored;
        return encryptionService.decrypt(stored);
    }

    private DatabaseEngine engineFor(DatabaseEngineType type) {
        if (type == DatabaseEngineType.POSTGRES) {
            return postgresEngine.orElseThrow(() -> new ProvisioningException("Postgres is not enabled"));
        }
        if (type == DatabaseEngineType.MYSQL) {
            return mysqlEngine.orElseThrow(() -> new ProvisioningException("MySQL is not enabled"));
        }
        return mongoEngine.orElseThrow(() -> new ProvisioningException("Mongo is not enabled"));
    }

    private MongoDatabaseRepository requireMongoRepository() {
        return mongoDatabaseRepository.orElseThrow(() -> new ProvisioningException("Mongo is not enabled"));
    }

    private String lockKey(DatabaseEngineType engine, String dbName) {
        return engine.name() + ":" + dbName;
    }

    public DatabaseInfo provision(CreateDatabaseForm form) {
        DatabaseEngineType engineType = form.engineType() != null ? form.engineType() : DatabaseEngineType.MONGO;
        String dbName = form.dbName().trim();
        String userName = form.userName().trim();
        String requestedPassword = form.password() == null ? "" : form.password().trim();
        if (engineType == DatabaseEngineType.POSTGRES) {
            nameValidator.validatePostgresDatabaseName(dbName);
            nameValidator.validatePostgresUserName(userName);
        } else if (engineType == DatabaseEngineType.MYSQL) {
            nameValidator.validateMysqlDatabaseName(dbName);
            nameValidator.validateMysqlUserName(userName);
        } else {
            nameValidator.validateMongoDatabaseName(dbName);
            nameValidator.validateUserName(userName);
        }
        nameValidator.validatePassword(requestedPassword);

        return databaseLocks.withLock(lockKey(engineType, dbName), () -> {
            if (managedDatabaseStore.existsByEngineTypeAndDbName(engineType, dbName)
                    || engineFor(engineType).databaseExists(dbName)) {
                throw new DatabaseAlreadyExistsException("Database '" + dbName + "' already exists in " + engineType);
            }

            String password = requestedPassword.isBlank()
                    ? passwordGenerator.generate(GENERATED_PASSWORD_LENGTH)
                    : requestedPassword;

            DatabaseEngine engine = engineFor(engineType);
            boolean pgUserCreated = false;
            boolean pgDbCreated = false;
            boolean mysqlUserCreated = false;
            boolean mysqlDbCreated = false;
            try {
                if (engineType == DatabaseEngineType.POSTGRES) {
                    engine.createUser(dbName, userName, password);
                    pgUserCreated = true;
                    engine.createDatabase(dbName, userName);
                    pgDbCreated = true;
                    engine.grantPrivileges(dbName, userName);
                } else if (engineType == DatabaseEngineType.MYSQL) {
                    engine.createUser(dbName, userName, password);
                    mysqlUserCreated = true;
                    engine.createDatabase(dbName, userName);
                    mysqlDbCreated = true;
                    engine.grantPrivileges(dbName, userName);
                } else {
                    engine.createUser(dbName, userName, password);
                    engine.createDatabase(dbName, userName);
                }
            } catch (MongoException e) {
                if (e instanceof MongoCommandException ce && ce.getErrorCode() == MONGO_CODE_USER_ALREADY_EXISTS) {
                    throw new DatabaseAlreadyExistsException("Database user '" + userName + "' already exists");
                }
                try { engine.dropUser(dbName, userName); } catch (Exception ce) { log.warn("Could not clean up partially created user '{}'", userName, ce); }
                log.error("Failed to provision database '{}' (user '{}')", dbName, userName, e);
                throw new ProvisioningException("Could not provision database '" + dbName + "'", e);
            } catch (Exception e) {
                if (engineType == DatabaseEngineType.POSTGRES) {
                    if (pgDbCreated) {
                        try { engine.dropDatabase(dbName); } catch (Exception ce) { log.warn("Could not clean up partially created PG database '{}'", dbName, ce); }
                    }
                    if (pgUserCreated) {
                        try { engine.dropUser(dbName, userName); } catch (Exception ce) { log.warn("Could not clean up partially created PG role '{}'", userName, ce); }
                    }
                } else if (engineType == DatabaseEngineType.MYSQL) {
                    if (mysqlDbCreated) {
                        try { engine.dropDatabase(dbName); } catch (Exception ce) { log.warn("Could not clean up partially created MySQL database '{}'", dbName, ce); }
                    }
                    if (mysqlUserCreated) {
                        try { engine.dropUser(dbName, userName); } catch (Exception ce) { log.warn("Could not clean up partially created MySQL user '{}'", userName, ce); }
                    }
                } else {
                    // MONGO generic failure (non-MongoException): best-effort cleanup
                    try { engine.dropDatabase(dbName); } catch (Exception ce) { log.warn("Could not clean up partially created MONGO database '{}'", dbName, ce); }
                    try { engine.dropUser(dbName, userName); } catch (Exception ce) { log.warn("Could not clean up partially created MONGO user '{}'", userName, ce); }
                }
                log.error("Failed to provision database '{}' (user '{}')", dbName, userName, e);
                throw new ProvisioningException("Could not provision database '" + dbName + "'", e);
            }

            java.time.Instant now = clock.instant();
            List<String> roles;
            if (engineType == DatabaseEngineType.POSTGRES) {
                roles = List.of("CONNECT:" + dbName);
            } else if (engineType == DatabaseEngineType.MYSQL) {
                roles = List.of("ALL:" + dbName);
            } else {
                roles = List.of("readWrite:" + dbName);
            }
            ManagedDatabase metadata = new ManagedDatabase(dbName, engineType, userName, roles, now, now, null);
            metadata.setStoredPassword(encryptPassword(password));
            managedDatabaseStore.save(metadata);
            audit(AuditEvent.PROVISION, dbName, engineType, userName, now);
            log.info("Provisioned {} database '{}' with user '{}'", engineType, dbName, userName);

            return toInfo(dbName, metadata, collectionCount(dbName, engineType), null, 0L)
                    .withConnectionString(engine.buildConnectionString(userName, password, dbName));
        });
    }

    public DatabaseInfo resetPassword(String dbName, ResetPasswordForm form) {
        // Legacy: lookup engine from metadata
        ManagedDatabase md = managedDatabaseStore.findByDbName(dbName)
                .orElseGet(() -> managedDatabaseStore.findAll().stream()
                        .filter(m -> m.getDbName().equals(dbName)).findFirst().orElse(null));
        if (md == null) throw new DatabaseNotFoundException("Database '" + dbName + "' is not provisioned");
        return resetPassword(md.getEngineType(), dbName, form);
    }

    public DatabaseInfo resetPassword(DatabaseEngineType engineType, String dbName, ResetPasswordForm form) {
        if (engineType == DatabaseEngineType.POSTGRES) nameValidator.validatePostgresDatabaseName(dbName);
        else if (engineType == DatabaseEngineType.MYSQL) nameValidator.validateMysqlDatabaseName(dbName);
        else nameValidator.validateDatabaseName(dbName);
        String requestedPassword = form.password() == null ? "" : form.password().trim();
        nameValidator.validatePassword(requestedPassword);

        return databaseLocks.withLock(lockKey(engineType, dbName), () -> {
            ManagedDatabase metadata = managedDatabaseStore.findByEngineTypeAndDbName(engineType, dbName)
                    .orElseThrow(() -> new DatabaseNotFoundException("Database '" + dbName + "' is not provisioned in " + engineType));

            String password = requestedPassword.isBlank()
                    ? passwordGenerator.generate(GENERATED_PASSWORD_LENGTH)
                    : requestedPassword;

            try {
                engineFor(engineType).updateUserPassword(dbName, metadata.getUserName(), password);
            } catch (MongoCommandException e) {
                log.error("Failed to reset password for user '{}' on database '{}'", metadata.getUserName(), dbName, e);
                throw new ProvisioningException("Could not reset password for database '" + dbName + "'", e);
            } catch (Exception e) {
                log.error("Failed to reset password for user '{}' on database '{}'", metadata.getUserName(), dbName, e);
                throw new ProvisioningException("Could not reset password for database '" + dbName + "'", e);
            }

            metadata.setStoredPassword(encryptPassword(password));
            metadata.setLastPasswordResetAt(clock.instant());
            managedDatabaseStore.save(metadata);
            audit(AuditEvent.RESET_PASSWORD, dbName, engineType, metadata.getUserName(), metadata.getLastPasswordResetAt());
            log.info("Reset password for user '{}' on database '{}' ({})", metadata.getUserName(), dbName, engineType);

            return toInfo(dbName, metadata, collectionCount(dbName, engineType), null, 0L)
                    .withConnectionString(engineFor(engineType).buildConnectionString(metadata.getUserName(), password, dbName));
        });
    }

    public void delete(String dbName) {
        // Legacy: try to find engine, delete all matching
        List<ManagedDatabase> metas = managedDatabaseStore.findByDbNameOrderByEngineType(dbName);
        if (!metas.isEmpty()) {
            for (ManagedDatabase m : metas) {
                delete(m.getEngineType(), dbName);
            }
            return;
        }
        // Not provisioned: try Mongo then Postgres then MySQL
        if (mongoEngine.isPresent() && mongoEngine.get().databaseExists(dbName)) {
            delete(DatabaseEngineType.MONGO, dbName);
        } else if (postgresEngine.isPresent() && postgresEngine.get().databaseExists(dbName)) {
            delete(DatabaseEngineType.POSTGRES, dbName);
        } else if (mysqlEngine.isPresent() && mysqlEngine.get().databaseExists(dbName)) {
            delete(DatabaseEngineType.MYSQL, dbName);
        } else {
            // Still try Mongo delete for idempotency
            delete(DatabaseEngineType.MONGO, dbName);
        }
    }

    public void delete(DatabaseEngineType engineType, String dbName) {
        if (engineType == DatabaseEngineType.POSTGRES) nameValidator.validatePostgresDatabaseName(dbName);
        else if (engineType == DatabaseEngineType.MYSQL) nameValidator.validateMysqlDatabaseName(dbName);
        else nameValidator.validateDatabaseName(dbName);
        databaseLocks.withLock(lockKey(engineType, dbName), () -> {
            Optional<ManagedDatabase> metadata = managedDatabaseStore.findByEngineTypeAndDbName(engineType, dbName);
            DatabaseEngine engine = engineFor(engineType);

            if (engineType == DatabaseEngineType.MONGO) {
                try { engine.dropDatabase(dbName); } catch (MongoCommandException e) {
                    if (!isMongoCode(e, MONGO_CODE_NAMESPACE_NOT_FOUND)) {
                        log.error("Failed to drop database '{}'", dbName, e);
                        throw new ProvisioningException("Could not drop database '" + dbName + "'", e);
                    }
                }
                metadata.ifPresent(m -> {
                    try { engine.dropUser(dbName, m.getUserName()); } catch (MongoCommandException e) {
                        if (!isMongoCode(e, MONGO_CODE_USER_NOT_FOUND)) {
                            log.error("Failed to drop user '{}' for database '{}'", m.getUserName(), dbName, e);
                            throw new ProvisioningException("Could not drop user for database '" + dbName + "'", e);
                        }
                    }
                });
                metadata.ifPresent(m -> managedDatabaseStore.deleteByEngineTypeAndDbName(engineType, dbName));
            } else {
                try { engine.dropDatabase(dbName); } catch (Exception e) {
                    log.warn("Failed to drop {} database '{}': {}", engineType, dbName, e.getMessage());
                }
                metadata.ifPresent(m -> {
                    try { engine.dropUser(dbName, m.getUserName()); } catch (Exception e) {
                        log.warn("Failed to drop {} role/user '{}': {}", engineType, m.getUserName(), e.getMessage());
                    }
                });
                metadata.ifPresent(m -> managedDatabaseStore.deleteByEngineTypeAndDbName(engineType, dbName));
            }
            audit(AuditEvent.DELETE, dbName, engineType, metadata.map(ManagedDatabase::getUserName).orElse(null), clock.instant());
            log.info("Deleted {} database '{}'", engineType, dbName);
        });
    }

    public void createCollection(String dbName, String collectionName) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateCollectionName(collectionName);
        MongoDatabaseRepository repo = requireMongoRepository();
        databaseLocks.withLock(lockKey(DatabaseEngineType.MONGO, dbName), () -> {
            requireDatabase(dbName, DatabaseEngineType.MONGO);
            if (repo.collectionExists(dbName, collectionName)) {
                throw new DatabaseAlreadyExistsException("Collection '" + collectionName + "' already exists");
            }
            try { repo.createCollection(dbName, collectionName); } catch (MongoCommandException e) {
                log.error("Failed to create collection {}.{}", dbName, collectionName, e);
                throw new ProvisioningException("Could not create collection '" + collectionName + "'", e);
            }
        });
    }

    public void dropCollection(String dbName, String collectionName) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateCollectionName(collectionName);
        MongoDatabaseRepository repo = requireMongoRepository();
        databaseLocks.withLock(lockKey(DatabaseEngineType.MONGO, dbName), () -> {
            requireDatabase(dbName, DatabaseEngineType.MONGO);
            if (!repo.collectionExists(dbName, collectionName)) {
                throw new DatabaseNotFoundException("Collection '" + collectionName + "' does not exist");
            }
            try { repo.dropCollection(dbName, collectionName); } catch (MongoCommandException e) {
                if (isMongoCode(e, MONGO_CODE_NAMESPACE_NOT_FOUND)) throw new DatabaseNotFoundException("Collection '" + collectionName + "' does not exist");
                log.error("Failed to drop collection {}.{}", dbName, collectionName, e);
                throw new ProvisioningException("Could not drop collection '" + collectionName + "'", e);
            }
        });
    }

    public List<DatabaseUser> listUsers(String dbName) {
        ManagedDatabase md = managedDatabaseStore.findByDbName(dbName).orElse(null);
        DatabaseEngineType engine = md != null ? md.getEngineType() : DatabaseEngineType.MONGO;
        return listUsers(engine, dbName);
    }

    public List<DatabaseUser> listUsers(DatabaseEngineType engineType, String dbName) {
        if (engineType == DatabaseEngineType.POSTGRES) nameValidator.validatePostgresDatabaseName(dbName);
        else if (engineType == DatabaseEngineType.MYSQL) nameValidator.validateMysqlDatabaseName(dbName);
        else nameValidator.validateDatabaseName(dbName);
        requireDatabase(dbName, engineType);
        if (engineType == DatabaseEngineType.POSTGRES) {
            return engineFor(engineType).getUsers(dbName).stream()
                    .map(u -> new DatabaseUser(u, List.of("CONNECT:" + dbName), dbName))
                    .toList();
        }
        if (engineType == DatabaseEngineType.MYSQL) {
            return engineFor(engineType).getUsers(dbName).stream()
                    .map(u -> new DatabaseUser(u, List.of("ALL:" + dbName), dbName))
                    .toList();
        }
        return requireMongoRepository().getUsers(dbName).stream()
                .map(doc -> new DatabaseUser(doc.getString("user"), roleNamesOf(doc), doc.getString("db")))
                .toList();
    }

    private static List<String> roleNamesOf(Document doc) {
        List<Document> roles = doc.getList("roles", Document.class);
        if (roles == null) return List.of();
        return roles.stream().map(role -> (role.getString("role") == null ? "" : role.getString("role")) + ":" + (role.getString("db") == null ? "" : role.getString("db"))).toList();
    }

    public void revokeUser(String dbName, String userName) {
        ManagedDatabase md = managedDatabaseStore.findByDbName(dbName).orElse(null);
        DatabaseEngineType engine = md != null ? md.getEngineType() : DatabaseEngineType.MONGO;
        revokeUser(engine, dbName, userName);
    }

    public void revokeUser(DatabaseEngineType engineType, String dbName, String userName) {
        if (engineType == DatabaseEngineType.POSTGRES) nameValidator.validatePostgresUserName(userName);
        else if (engineType == DatabaseEngineType.MYSQL) nameValidator.validateMysqlUserName(userName);
        else nameValidator.validateUserName(userName);
        if (engineType == DatabaseEngineType.POSTGRES) nameValidator.validatePostgresDatabaseName(dbName);
        else if (engineType == DatabaseEngineType.MYSQL) nameValidator.validateMysqlDatabaseName(dbName);
        else nameValidator.validateDatabaseName(dbName);
        databaseLocks.withLock(lockKey(engineType, dbName), () -> {
            requireDatabase(dbName, engineType);
            if (engineType == DatabaseEngineType.POSTGRES) {
                try { engineFor(engineType).dropUser(dbName, userName); } catch (Exception e) {
                    throw new ProvisioningException("Could not revoke user '" + userName + "'", e);
                }
                audit(AuditEvent.REVOKE_USER, dbName, engineType, userName, clock.instant());
                log.info("Revoked PG user '{}' from database '{}'", userName, dbName);
                return;
            }
            if (engineType == DatabaseEngineType.MYSQL) {
                try { engineFor(engineType).dropUser(dbName, userName); } catch (Exception e) {
                    throw new ProvisioningException("Could not revoke user '" + userName + "'", e);
                }
                audit(AuditEvent.REVOKE_USER, dbName, engineType, userName, clock.instant());
                log.info("Revoked MySQL user '{}' from database '{}'", userName, dbName);
                return;
            }
            List<Document> users = requireMongoRepository().getUsers(dbName);
            if (users.size() <= 1) throw new DatabaseAlreadyExistsException("Cannot revoke the last user of database '" + dbName + "'. Delete the database instead.");
            try { requireMongoRepository().dropUser(dbName, userName); } catch (MongoCommandException e) {
                if (isMongoCode(e, MONGO_CODE_USER_NOT_FOUND)) throw new DatabaseNotFoundException("User '" + userName + "' does not exist in database '" + dbName + "'");
                log.error("Failed to revoke user '{}' from database '{}'", userName, dbName, e);
                throw new ProvisioningException("Could not revoke user '" + userName + "'", e);
            }
            audit(AuditEvent.REVOKE_USER, dbName, engineType, userName, clock.instant());
            log.info("Revoked user '{}' from database '{}'", userName, dbName);
        });
    }

    public List<DatabaseInfo> listDatabases() {
        // Legacy: Mongo only for backward compat
        return listDatabases(DatabaseEngineType.MONGO);
    }

    public List<DatabaseInfo> listDatabases(DatabaseEngineType engineType) {
        Map<String, ManagedDatabase> byName = managedDatabaseStore.findAllByEngineType(engineType).stream()
                .collect(Collectors.toMap(ManagedDatabase::getDbName, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, Long> sizes;
        try { sizes = engineFor(engineType).getDatabaseSizes(); } catch (Exception e) {
            log.error("Could not read database sizes from {}", engineType, e);
            throw new ProvisioningException("Could not read database sizes", e);
        }
        List<String> dbNames;
        try { dbNames = engineFor(engineType).listDatabaseNames(); } catch (MongoException e) {
            log.error("Could not list databases on {}", engineType, e);
            throw new ProvisioningException("Could not list databases on " + engineType, e);
        } catch (Exception e) {
            log.error("Could not list databases on {}", engineType, e);
            throw new ProvisioningException("Could not list databases on " + engineType, e);
        }
        Set<String> systemDbs;
        if (engineType == DatabaseEngineType.POSTGRES) systemDbs = DatabaseNameValidator.POSTGRES_SYSTEM_DATABASES;
        else if (engineType == DatabaseEngineType.MYSQL) systemDbs = DatabaseNameValidator.MYSQL_SYSTEM_DATABASES;
        else systemDbs = DatabaseNameValidator.SYSTEM_DATABASES;
        return dbNames.stream()
                .filter(dbName -> !systemDbs.contains(dbName.toLowerCase(Locale.ROOT)))
                .map(dbName -> toInfo(dbName, engineType, byName.get(dbName), collectionCount(dbName, engineType), null, sizes.getOrDefault(dbName, 0L)))
                .sorted(Comparator.comparing(DatabaseInfo::dbName))
                .toList();
    }

    public DatabaseInfo getDatabase(String dbName) {
        // Try Mongo first, then Postgres, then MySQL
        ManagedDatabase md = managedDatabaseStore.findByDbName(dbName).orElse(null);
        if (md != null) return getDatabase(md.getEngineType(), dbName);
        if (mongoEngine.isPresent() && mongoEngine.get().databaseExists(dbName)) return getDatabase(DatabaseEngineType.MONGO, dbName);
        if (postgresEngine.isPresent() && postgresEngine.get().databaseExists(dbName)) return getDatabase(DatabaseEngineType.POSTGRES, dbName);
        if (mysqlEngine.isPresent() && mysqlEngine.get().databaseExists(dbName)) return getDatabase(DatabaseEngineType.MYSQL, dbName);
        throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
    }

    public DatabaseInfo getDatabase(DatabaseEngineType engineType, String dbName) {
        if (engineType == DatabaseEngineType.POSTGRES) nameValidator.validatePostgresDatabaseName(dbName);
        else if (engineType == DatabaseEngineType.MYSQL) nameValidator.validateMysqlDatabaseName(dbName);
        else nameValidator.validateDatabaseName(dbName);
        if (!engineFor(engineType).databaseExists(dbName)) throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist in " + engineType);
        Optional<ManagedDatabase> metadata = managedDatabaseStore.findByEngineTypeAndDbName(engineType, dbName);
        ManagedDatabase md = metadata.orElse(null);
        String connectionString = null;
        if (md != null && md.getStoredPassword() != null) {
            String plain = decryptPassword(md.getStoredPassword());
            connectionString = engineFor(engineType).buildConnectionString(md.getUserName(), plain, dbName);
        }
        long size = 0L;
        try { size = engineFor(engineType).getDatabaseSizes().getOrDefault(dbName, 0L); } catch (Exception ignored) {}
        return toInfo(dbName, engineType, md, collectionCount(dbName, engineType), connectionString, size);
    }

    String resolveConnectionHost() {
        return mongoEngine.map(MongoDatabaseEngine::resolveConnectionHost).orElse("127.0.0.1:9812");
    }

    private void requireDatabase(String dbName, DatabaseEngineType engineType) {
        if (!engineFor(engineType).databaseExists(dbName)) throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist in " + engineType);
    }

    private void audit(String eventType, String dbName, DatabaseEngineType engineType, String userName, java.time.Instant performedAt) {
        AuditEvent event = new AuditEvent(eventType, dbName, engineType, userName, currentUsername(), performedAt);
        auditStore.save(event);
        applicationEventPublisher.publishEvent(new AuditEventRecorded(event));
    }

    private void audit(String eventType, String dbName, String userName, java.time.Instant performedAt) {
        audit(eventType, dbName, DatabaseEngineType.MONGO, userName, performedAt);
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null ? authentication.getName() : "unknown";
    }

    private Long collectionCount(String dbName, DatabaseEngineType engineType) {
        if (engineType == DatabaseEngineType.POSTGRES || engineType == DatabaseEngineType.MYSQL) return null;
        try { return (long) requireMongoRepository().listCollectionNames(dbName).size(); } catch (MongoException e) {
            log.warn("Could not count collections of {}; leaving count blank", dbName, e);
            return null;
        }
    }

    private Long collectionCount(String dbName) { return collectionCount(dbName, DatabaseEngineType.MONGO); }

    private Map<String, Long> readDatabaseSizes() {
        try { return requireMongoRepository().getDatabaseSizes(); } catch (MongoException e) {
            log.error("Could not read database sizes from the MongoDB server", e);
            throw new ProvisioningException("Could not read database sizes", e);
        }
    }

    private DatabaseInfo toInfo(String dbName, ManagedDatabase metadata, Long collectionsCount, String connectionString, long sizeBytes) {
        if (metadata == null) return new DatabaseInfo(dbName, null, null, List.of(), collectionsCount, null, null, null, false, connectionString, sizeBytes);
        return new DatabaseInfo(dbName, metadata.getEngineType(), metadata.getUserName(), metadata.getRoles(), collectionsCount, metadata.getCreatedAt(), metadata.getUpdatedAt(), metadata.getLastPasswordResetAt(), true, connectionString, sizeBytes);
    }

    private DatabaseInfo toInfo(String dbName, DatabaseEngineType engineType, ManagedDatabase metadata, Long collectionsCount, String connectionString, long sizeBytes) {
        if (metadata == null) return new DatabaseInfo(dbName, engineType, null, List.of(), collectionsCount, null, null, null, false, connectionString, sizeBytes);
        return toInfo(dbName, metadata, collectionsCount, connectionString, sizeBytes);
    }

    private boolean isMongoCode(MongoCommandException e, int code) { return e.getErrorCode() == code; }
}
