package com.pkmprojects.mongodbserver.model;

/**
 * PgBouncer pool mode exposed to applications. Only TRANSACTION is
 * supported — session pooling is intentionally not offered so the
 * transaction-pooling limitations stay explicit in API/UI/docs.
 */
public enum PoolMode {
    TRANSACTION
}
