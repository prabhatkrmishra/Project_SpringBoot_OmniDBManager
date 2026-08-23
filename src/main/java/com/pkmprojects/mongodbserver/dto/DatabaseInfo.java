package com.pkmprojects.mongodbserver.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * View model for a database shown on the dashboard / detail page.
 * {@code connectionString} is populated after creation/reset (flash message)
 * and reconstructed from stored provisioning metadata for provisioned databases.
 * {@code collectionsCount} is {@code null} when the count could not be read
 * (e.g. MongoDB briefly unreachable) - views render that as an em dash, never 0.
 *
 * <p>Serializable because the show-once message travels as a flash attribute,
 * and flash attributes are stored in the HTTP session (JDK serialization).
 */
public record DatabaseInfo(
        String dbName,
        String userName,
        List<String> roles,
        Long collectionsCount,
        Instant createdAt,
        Instant updatedAt,
        Instant lastPasswordResetAt,
        boolean provisioned,
        String connectionString,
        long sizeBytes) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @return a copy of this view model with the show-once connection string set
     */
    public DatabaseInfo withConnectionString(String connectionString) {
        return new DatabaseInfo(dbName, userName, roles, collectionsCount, createdAt, updatedAt,
                lastPasswordResetAt, provisioned, connectionString, sizeBytes);
    }
}
