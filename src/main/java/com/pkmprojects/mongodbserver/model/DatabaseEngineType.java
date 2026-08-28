package com.pkmprojects.mongodbserver.model;

/**
 * Engine that owns a provisioned database. Composite identity is
 * {@code engineType + ":" + dbName} so the same name can exist in all engines.
 */
public enum DatabaseEngineType {
    MONGO,
    POSTGRES,
    MYSQL
}
