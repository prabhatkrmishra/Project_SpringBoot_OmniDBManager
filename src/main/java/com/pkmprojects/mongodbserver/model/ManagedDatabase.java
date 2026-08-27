package com.pkmprojects.mongodbserver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Metadata about a provisioned database: which engine/user owns it, when it was
 * created, and when its password was last reset. Stored in the {@code mongodb_admin}
 * database. Composite identity is {@code engineType + ":" + dbName} so the same
 * name can exist in both engines. Existing docs without {@code engineType} are
 * treated as {@code MONGO} for backward compatibility.
 */
@Document(collection = "provisioned_databases")
public class ManagedDatabase {

    @Id
    private String id;

    private String dbName;

    private DatabaseEngineType engineType;

    private String userName;

    private List<String> roles;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant lastPasswordResetAt;

    private String storedPassword;

    public ManagedDatabase() {
        // for Spring Data
    }

    /**
     * Creates provisioned metadata for a database.
     *
     * @param dbName              database name
     * @param engineType          owning engine
     * @param userName            dedicated user name
     * @param roles               roles granted to the user
     * @param createdAt           provisioning time
     * @param updatedAt           last update time
     * @param lastPasswordResetAt last password rotation, or {@code null} if never
     */
    public ManagedDatabase(String dbName, DatabaseEngineType engineType, String userName, List<String> roles,
                           Instant createdAt, Instant updatedAt, Instant lastPasswordResetAt) {
        this.id = engineType.name() + ":" + dbName;
        this.dbName = dbName;
        this.engineType = engineType;
        this.userName = userName;
        this.roles = List.copyOf(roles);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastPasswordResetAt = lastPasswordResetAt;
    }

    /**
     * Legacy constructor for Mongo-only call sites — defaults to MONGO.
     */
    public ManagedDatabase(String dbName, String userName, List<String> roles,
                           Instant createdAt, Instant updatedAt, Instant lastPasswordResetAt) {
        this(dbName, DatabaseEngineType.MONGO, userName, roles, createdAt, updatedAt, lastPasswordResetAt);
    }

    public String getId() {
        return id;
    }

    public String getDbName() {
        return dbName;
    }

    public DatabaseEngineType getEngineType() {
        return engineType != null ? engineType : DatabaseEngineType.MONGO;
    }

    public void setEngineType(DatabaseEngineType engineType) {
        this.engineType = engineType;
    }

    public String getUserName() {
        return userName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastPasswordResetAt() {
        return lastPasswordResetAt;
    }

    public String getStoredPassword() {
        return storedPassword;
    }

    public void setStoredPassword(String storedPassword) {
        this.storedPassword = storedPassword;
    }

    /**
     * Records a password rotation; also bumps {@code updatedAt} to the same
     * instant so the metadata reflects the last change.
     */
    public void setLastPasswordResetAt(Instant lastPasswordResetAt) {
        this.lastPasswordResetAt = lastPasswordResetAt;
        this.updatedAt = lastPasswordResetAt;
    }
}
