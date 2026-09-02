package com.pkmprojects.mongodbserver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * One admin action on the provisioning lifecycle, stored in the
 * {@code admin_activity} collection for auditability. Passwords are never part
 * of these records.
 */
@Document(collection = "admin_activity")
public class AuditEvent {

    /**
     * Event type: a database was provisioned.
     */
    public static final String PROVISION = "PROVISION";
    /**
     * Event type: a provisioned user's password was rotated.
     */
    public static final String RESET_PASSWORD = "RESET_PASSWORD";
    /**
     * Event type: a database was deleted.
     */
    public static final String DELETE = "DELETE";
    /**
     * Event type: a database user was revoked.
     */
    public static final String REVOKE_USER = "REVOKE_USER";
    /**
     * Event type: a webhook endpoint was created.
     */
    public static final String WEBHOOK_CREATED = "WEBHOOK_CREATED";
    /**
     * Event type: a webhook endpoint was enabled or disabled.
     */
    public static final String WEBHOOK_UPDATED = "WEBHOOK_UPDATED";
    /**
     * Event type: a webhook endpoint was deleted.
     */
    public static final String WEBHOOK_DELETED = "WEBHOOK_DELETED";
    /**
     * Event type: a database backup was downloaded/created.
     */
    public static final String BACKUP_CREATED = "BACKUP_CREATED";
    /**
     * Event type: a database was restored from a backup file.
     */
    public static final String BACKUP_RESTORED = "BACKUP_RESTORED";
    /**
     * Event type: documents were bulk-imported into a collection.
     */
    public static final String IMPORT = "IMPORT";
    public static final String TABLE_CREATED = "TABLE_CREATED";
    public static final String TABLE_DROPPED = "TABLE_DROPPED";
    public static final String TABLE_TRUNCATED = "TABLE_TRUNCATED";
    public static final String ROW_INSERTED = "ROW_INSERTED";
    public static final String ROW_DELETED = "ROW_DELETED";
    public static final String VECTOR_ENABLED = "VECTOR_ENABLED";

    /**
     * Every event type, in display order. Used by the activity filter and the
     * webhook subscription checkboxes.
     */
    public static final List<String> ALL_TYPES = List.of(
            PROVISION, RESET_PASSWORD, DELETE, REVOKE_USER,
            WEBHOOK_CREATED, WEBHOOK_UPDATED, WEBHOOK_DELETED,
            BACKUP_CREATED, BACKUP_RESTORED, IMPORT,
            TABLE_CREATED, TABLE_DROPPED, TABLE_TRUNCATED, ROW_INSERTED, ROW_DELETED,
            VECTOR_ENABLED);

    @Id
    private String id;

    private String eventType;

    private String dbName;

    private DatabaseEngineType engineType;

    private String userName;

    private String performedBy;

    private Instant performedAt;

    public AuditEvent() {
        // for Spring Data
    }

    /**
     * Records one admin action on the provisioning lifecycle.
     *
     * @param eventType   one of {@link #PROVISION}, {@link #RESET_PASSWORD}, {@link #DELETE},
     *                    {@link #REVOKE_USER}, {@link #WEBHOOK_CREATED}, {@link #WEBHOOK_UPDATED},
     *                    {@link #WEBHOOK_DELETED}, {@link #BACKUP_CREATED}, {@link #BACKUP_RESTORED},
     *                    {@link #IMPORT}, {@link #TABLE_CREATED}, {@link #TABLE_DROPPED},
     *                    {@link #TABLE_TRUNCATED}, {@link #ROW_INSERTED}, {@link #ROW_DELETED},
     *                    {@link #VECTOR_ENABLED}
     * @param dbName      affected database
     * @param userName    affected database user, or {@code null} (e.g. delete of a
     *                    database that was never provisioned, or vector operations)
     * @param performedBy admin username from the security context
     * @param performedAt action timestamp
     */
    public AuditEvent(String eventType, String dbName, String userName, String performedBy, Instant performedAt) {
        this(eventType, dbName, null, userName, performedBy, performedAt);
    }

    public AuditEvent(String eventType, String dbName, DatabaseEngineType engineType, String userName, String performedBy, Instant performedAt) {
        this.eventType = eventType;
        this.dbName = dbName;
        this.engineType = engineType;
        this.userName = userName;
        this.performedBy = performedBy;
        this.performedAt = performedAt;
    }

    public String getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getDbName() {
        return dbName;
    }

    public DatabaseEngineType getEngineType() {
        return engineType;
    }

    public String getUserName() {
        return userName;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }
}
