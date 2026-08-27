package com.pkmprojects.mongodbserver.dto;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Form backing object for provisioning a new database with a dedicated user.
 * Blank {@code password} means "generate one" (handled in the service).
 */
public record CreateDatabaseForm(

        @NotBlank(message = "Database name is required")
        @Size(max = 64, message = "Database name must be at most 64 characters")
        String dbName,

        DatabaseEngineType engineType,

        @NotBlank(message = "Database user name is required")
        @Size(max = 64, message = "Database user name must be at most 64 characters")
        String userName,

        @Size(max = 128, message = "Password must be at most 128 characters")
        String password) {

    /**
     * Legacy 3-arg constructor defaults to MONGO for backward compat in tests.
     */
    public CreateDatabaseForm(String dbName, String userName, String password) {
        this(dbName, DatabaseEngineType.MONGO, userName, password);
    }
}
