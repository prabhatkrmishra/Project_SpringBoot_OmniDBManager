package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import org.bson.Document;
import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.DatabaseUser;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.security.PasswordGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates the Atlas-style provisioning lifecycle:
 * create a database with a dedicated db-scoped user, rotate its password,
 * and delete the database together with its user.
 *
 * <p>The database user's password is persisted in provisioning metadata so the
 * connection string can be reconstructed and shown on the database detail page
 * at any time. Every lifecycle action is recorded in the {@code admin_activity}
 * audit trail.</p>
 *
 * <p><strong>Concurrency contract:</strong> all lifecycle operations for the
 * same database name are serialized per name (see {@link DatabaseLockRegistry}).
 * Without this, concurrent {@link #provision(CreateDatabaseForm)} and
 * {@link #delete(String)} calls interleave into inconsistent states (orphaned
 * metadata, a database whose user was already dropped) and concurrent
 * {@code createUser} commands against a brand-new database can lose the user
 * insert entirely while still reporting success - MongoDB does not serialize
 * user creation on a not-yet-existing database. Different database names are
 * never blocked by each other.</p>
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private static final int GENERATED_PASSWORD_LENGTH = 16;
    private static final int MONGO_CODE_USER_NOT_FOUND = 11;
    private static final int MONGO_CODE_NAMESPACE_NOT_FOUND = 26;
    private static final int MONGO_CODE_USER_ALREADY_EXISTS = 51003;

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final ManagedDatabaseRepository managedDatabaseRepository;
    private final AuditLogRepository auditLogRepository;
    private final MongoNameValidator nameValidator;
    private final PasswordGenerator passwordGenerator;
    private final Clock clock;
    private final Environment environment;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DatabaseLockRegistry databaseLocks;

    public ProvisioningService(MongoDatabaseRepository mongoDatabaseRepository,
                               ManagedDatabaseRepository managedDatabaseRepository,
                               AuditLogRepository auditLogRepository,
                               MongoNameValidator nameValidator,
                               PasswordGenerator passwordGenerator,
                               Clock clock,
                               Environment environment,
                               ApplicationEventPublisher applicationEventPublisher,
                               DatabaseLockRegistry databaseLocks) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.managedDatabaseRepository = managedDatabaseRepository;
        this.auditLogRepository = auditLogRepository;
        this.nameValidator = nameValidator;
        this.passwordGenerator = passwordGenerator;
        this.clock = clock;
        this.environment = environment;
        this.applicationEventPublisher = applicationEventPublisher;
        this.databaseLocks = databaseLocks;
    }

    /**
     * Percent-encodes a URI userinfo component (unreserved characters kept as-is).
     */
    private static String uriEncode(String value) {
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

    /**
     * Creates a database and a Mongo user with readWrite rights scoped to it.
     * The returned {@link DatabaseInfo} carries the connection string (with password)
     * for the "show once" flash message.
     */
    public DatabaseInfo provision(CreateDatabaseForm form) {
        String dbName = form.dbName().trim();
        String userName = form.userName().trim();
        String requestedPassword = form.password() == null ? "" : form.password().trim();
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateUserName(userName);
        nameValidator.validatePassword(requestedPassword);

        return databaseLocks.withLock(dbName, () -> {
            if (managedDatabaseRepository.existsByDbName(dbName) || mongoDatabaseRepository.databaseExists(dbName)) {
                throw new DatabaseAlreadyExistsException("Database '" + dbName + "' already exists");
            }

            String password = requestedPassword.isBlank()
                    ? passwordGenerator.generate(GENERATED_PASSWORD_LENGTH)
                    : requestedPassword;

            try {
                mongoDatabaseRepository.createUser(dbName, userName, password);
                mongoDatabaseRepository.createDatabase(dbName);
            } catch (MongoException e) {
                if (e instanceof MongoCommandException commandException
                        && commandException.getErrorCode() == MONGO_CODE_USER_ALREADY_EXISTS) {
                    // lost a concurrent provision race - the duplicate user already exists
                    throw new DatabaseAlreadyExistsException("Database user '" + userName + "' already exists");
                }
                // Best-effort cleanup of a partially created user so a retry is not blocked.
                // Widened to MongoException: a timeout/connection failure after the user was
                // created would otherwise leak an orphaned user.
                try {
                    mongoDatabaseRepository.dropUser(dbName, userName);
                } catch (MongoException cleanupFailure) {
                    log.warn("Could not clean up partially created user '{}' after failed provisioning", userName,
                            cleanupFailure);
                }
                log.error("Failed to provision database '{}' (user '{}')", dbName, userName, e);
                throw new ProvisioningException("Could not provision database '" + dbName + "'", e);
            }

            Instant now = clock.instant();
            ManagedDatabase metadata = new ManagedDatabase(dbName, userName, List.of("readWrite:" + dbName), now, now, null);
            metadata.setStoredPassword(password);
            managedDatabaseRepository.save(metadata);
            audit(AuditEvent.PROVISION, dbName, userName, now);
            log.info("Provisioned database '{}' with user '{}'", dbName, userName);

            return toInfo(dbName, metadata, collectionCount(dbName), null, 0L)
                    .withConnectionString(buildConnectionString(userName, password, dbName));
        });
    }

    /**
     * Rotates the provisioned user's password. Returns the new connection string
     * (shown once).
     */
    public DatabaseInfo resetPassword(String dbName, ResetPasswordForm form) {
        nameValidator.validateDatabaseName(dbName);
        String requestedPassword = form.password() == null ? "" : form.password().trim();
        nameValidator.validatePassword(requestedPassword);

        return databaseLocks.withLock(dbName, () -> {
            ManagedDatabase metadata = managedDatabaseRepository.findByDbName(dbName)
                    .orElseThrow(() -> new DatabaseNotFoundException("Database '" + dbName + "' is not provisioned"));

            String password = requestedPassword.isBlank()
                    ? passwordGenerator.generate(GENERATED_PASSWORD_LENGTH)
                    : requestedPassword;

            try {
                mongoDatabaseRepository.updateUserPassword(dbName, metadata.getUserName(), password);
            } catch (MongoCommandException e) {
                log.error("Failed to reset password for user '{}' on database '{}'", metadata.getUserName(), dbName, e);
                throw new ProvisioningException("Could not reset password for database '" + dbName + "'", e);
            }

            metadata.setStoredPassword(password);
            metadata.setLastPasswordResetAt(clock.instant());
            managedDatabaseRepository.save(metadata);
            audit(AuditEvent.RESET_PASSWORD, dbName, metadata.getUserName(), metadata.getLastPasswordResetAt());
            log.info("Reset password for user '{}' on database '{}'", metadata.getUserName(), dbName);

            return toInfo(dbName, metadata, collectionCount(dbName), null, 0L)
                    .withConnectionString(buildConnectionString(metadata.getUserName(), password, dbName));
        });
    }

    /**
     * Drops the database and (if provisioned) its user and metadata. Tolerates a
     * database whose namespace or user is already gone (e.g. an earlier partial
     * failure), so delete is always retryable.
     */
    public void delete(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        databaseLocks.withLock(dbName, () -> {
            Optional<ManagedDatabase> metadata = managedDatabaseRepository.findByDbName(dbName);

            try {
                mongoDatabaseRepository.dropDatabase(dbName);
            } catch (MongoCommandException e) {
                if (!isMongoCode(e, MONGO_CODE_NAMESPACE_NOT_FOUND)) {
                    log.error("Failed to drop database '{}'", dbName, e);
                    throw new ProvisioningException("Could not drop database '" + dbName + "'", e);
                }
            }

            metadata.ifPresent(m -> {
                try {
                    mongoDatabaseRepository.dropUser(dbName, m.getUserName());
                } catch (MongoCommandException e) {
                    if (!isMongoCode(e, MONGO_CODE_USER_NOT_FOUND)) {
                        log.error("Failed to drop user '{}' for database '{}'", m.getUserName(), dbName, e);
                        throw new ProvisioningException("Could not drop user for database '" + dbName + "'", e);
                    }
                }
            });

            metadata.ifPresent(m -> managedDatabaseRepository.deleteByDbName(dbName));
            audit(AuditEvent.DELETE, dbName, metadata.map(ManagedDatabase::getUserName).orElse(null), clock.instant());
            log.info("Deleted database '{}'", dbName);
        });
    }

    /**
     * Creates a collection inside an existing database. The database must already
     * exist - MongoDB would otherwise create it implicitly, which is not what an
     * admin expects when a typo sneaks into the database name.
     */
    public void createCollection(String dbName, String collectionName) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateCollectionName(collectionName);
        databaseLocks.withLock(dbName, () -> {
            requireDatabase(dbName);
            if (mongoDatabaseRepository.collectionExists(dbName, collectionName)) {
                throw new DatabaseAlreadyExistsException("Collection '" + collectionName + "' already exists");
            }
            try {
                mongoDatabaseRepository.createCollection(dbName, collectionName);
            } catch (MongoCommandException e) {
                log.error("Failed to create collection {}.{}", dbName, collectionName, e);
                throw new ProvisioningException("Could not create collection '" + collectionName + "'", e);
            }
        });
    }

    /**
     * Drops a collection inside an existing database. Throws
     * {@link DatabaseNotFoundException} when the collection (or database) is
     * already gone, so the action is retryable.
     */
    public void dropCollection(String dbName, String collectionName) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateCollectionName(collectionName);
        databaseLocks.withLock(dbName, () -> {
            requireDatabase(dbName);
            if (!mongoDatabaseRepository.collectionExists(dbName, collectionName)) {
                throw new DatabaseNotFoundException("Collection '" + collectionName + "' does not exist");
            }
            try {
                mongoDatabaseRepository.dropCollection(dbName, collectionName);
            } catch (MongoCommandException e) {
                if (isMongoCode(e, MONGO_CODE_NAMESPACE_NOT_FOUND)) {
                    throw new DatabaseNotFoundException("Collection '" + collectionName + "' does not exist");
                }
                log.error("Failed to drop collection {}.{}", dbName, collectionName, e);
                throw new ProvisioningException("Could not drop collection '" + collectionName + "'", e);
            }
        });
    }

    /**
     * Lists all users defined in {@code dbName} (excluding system users).
     *
     * @throws DatabaseNotFoundException when the database does not exist
     */
    public List<DatabaseUser> listUsers(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        requireDatabase(dbName);
        return mongoDatabaseRepository.getUsers(dbName).stream()
                .map(doc -> new DatabaseUser(
                        doc.getString("user"),
                        roleNamesOf(doc),
                        doc.getString("db")))
                .toList();
    }

    private static List<String> roleNamesOf(Document doc) {
        List<Document> roles = doc.getList("roles", Document.class);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (role.getString("role") == null ? "" : role.getString("role"))
                        + ":" + (role.getString("db") == null ? "" : role.getString("db")))
                .toList();
    }

    /**
     * Revokes a user's access to a database by dropping the user. Refuses to
     * drop the last remaining user to prevent locking out the database entirely.
     *
     * @throws DatabaseNotFoundException when the database does not exist
     * @throws DatabaseAlreadyExistsException when trying to drop the last user
     */
    public void revokeUser(String dbName, String userName) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateUserName(userName);
        databaseLocks.withLock(dbName, () -> {
            requireDatabase(dbName);
            List<Document> users = mongoDatabaseRepository.getUsers(dbName);
            if (users.size() <= 1) {
                throw new DatabaseAlreadyExistsException(
                        "Cannot revoke the last user of database '" + dbName + "'. Delete the database instead.");
            }
            try {
                mongoDatabaseRepository.dropUser(dbName, userName);
            } catch (MongoCommandException e) {
                if (isMongoCode(e, MONGO_CODE_USER_NOT_FOUND)) {
                    throw new DatabaseNotFoundException("User '" + userName + "' does not exist in database '" + dbName + "'");
                }
                log.error("Failed to revoke user '{}' from database '{}'", userName, dbName, e);
                throw new ProvisioningException("Could not revoke user '" + userName + "'", e);
            }
            audit(AuditEvent.REVOKE_USER, dbName, userName, clock.instant());
            log.info("Revoked user '{}' from database '{}'", userName, dbName);
        });
    }

    /**
     * All user-manageable databases (system and metadata DBs excluded), sorted by name.
     */
    public List<DatabaseInfo> listDatabases() {
        Map<String, ManagedDatabase> byName = managedDatabaseRepository.findAll().stream()
                .collect(Collectors.toMap(ManagedDatabase::getDbName, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> sizes = readDatabaseSizes();

        List<String> dbNames;
        try {
            dbNames = mongoDatabaseRepository.listDatabaseNames();
        } catch (MongoException e) {
            log.error("Could not list databases on the MongoDB server", e);
            throw new ProvisioningException("Could not list databases on the MongoDB server", e);
        }

        return dbNames.stream()
                .filter(dbName -> !MongoNameValidator.SYSTEM_DATABASES.contains(dbName.toLowerCase(Locale.ROOT)))
                .map(dbName -> toInfo(dbName, byName.get(dbName), collectionCount(dbName), null,
                        sizes.getOrDefault(dbName, 0L)))
                .sorted(Comparator.comparing(DatabaseInfo::dbName))
                .toList();
    }

    /**
     * Returns the details of one database (whether provisioned or not).
     *
     * @throws DatabaseNotFoundException when the database does not exist on the server
     */
    public DatabaseInfo getDatabase(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
        Optional<ManagedDatabase> metadata = managedDatabaseRepository.findByDbName(dbName);
        ManagedDatabase md = metadata.orElse(null);
        // Rebuild the connection string from the stored password so the detail
        // page always shows it without relying on the one-time flash message.
        String connectionString = null;
        if (md != null && md.getStoredPassword() != null) {
            connectionString = buildConnectionString(md.getUserName(), md.getStoredPassword(), dbName);
        }
        long size = readDatabaseSizes().getOrDefault(dbName, 0L);
        return toInfo(dbName, md, collectionCount(dbName), connectionString, size);
    }

    /**
     * Host portion for connection strings: the explicit
     * {@code app.mongo-public-host} (e.g. {@code mongo.pkmprojects.online:9812})
     * when set, otherwise derived from the active {@code spring.mongodb.uri}
     * (e.g. Atlas cluster) or 127.0.0.1:9812.
     */
    String resolveConnectionHost() {
        String publicHost = environment.getProperty("app.mongo-public-host", "");
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost;
        }
        String uri = environment.getProperty("spring.mongodb.uri", "");
        if (uri.isBlank()) {
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

    private void requireDatabase(String dbName) {
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
    }

    private void audit(String eventType, String dbName, String userName, Instant performedAt) {
        AuditEvent event = new AuditEvent(eventType, dbName, userName, currentUsername(), performedAt);
        auditLogRepository.save(event);
        applicationEventPublisher.publishEvent(new AuditEventRecorded(event));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null ? authentication.getName() : "unknown";
    }

    private String buildConnectionString(String userName, String password, String dbName) {
        // RFC 3986: '@', '/', '?', '#', '%' and friends inside credentials must be
        // percent-encoded or the generated URI is not dialable. Generated passwords
        // deliberately contain '@', '#', '%', so this is not a corner case.
        //
        // The provisioned user lives in <db>.system.users, so the URI path names the
        // database and the explicit authSource keeps authentication unambiguous for
        // every driver. Consumers connect directly to MongoDB with this string - the
        // app is only the credential-issuing control plane, never a data-plane proxy.
        return "mongodb://" + uriEncode(userName) + ":" + uriEncode(password) + "@" + resolveConnectionHost() + "/" + dbName
                + "?authSource=" + dbName;
    }

    private int collectionCount(String dbName) {
        try {
            return mongoDatabaseRepository.listCollectionNames(dbName).size();
        } catch (MongoException e) {
            // A collection listing failing for one database (e.g. the server is
            // briefly unreachable) must not fail the whole dashboard page with a
            // 500 - degrade to "unknown" for that row and say so in the log.
            log.warn("Could not count collections of {}; leaving count blank", dbName, e);
            return 0;
        }
    }

    /**
     * Reads database sizes, translating a driver failure (timeout, unreachable
     * server) into a {@link ProvisioningException} instead of letting a raw
     * {@link MongoException} escape to the error handler as an opaque 500.
     */
    private Map<String, Long> readDatabaseSizes() {
        try {
            return mongoDatabaseRepository.getDatabaseSizes();
        } catch (MongoException e) {
            log.error("Could not read database sizes from the MongoDB server", e);
            throw new ProvisioningException("Could not read database sizes", e);
        }
    }

    private DatabaseInfo toInfo(String dbName, ManagedDatabase metadata, Integer collectionsCount,
                                String connectionString, long sizeBytes) {
        if (metadata == null) {
            return new DatabaseInfo(dbName, null, List.of(), collectionsCount, null, null, null, false,
                    connectionString, sizeBytes);
        }
        return new DatabaseInfo(dbName, metadata.getUserName(), metadata.getRoles(),
                collectionsCount, metadata.getCreatedAt(), metadata.getUpdatedAt(),
                metadata.getLastPasswordResetAt(), true, connectionString, sizeBytes);
    }

    private boolean isMongoCode(MongoCommandException e, int code) {
        return e.getErrorCode() == code;
    }
}
