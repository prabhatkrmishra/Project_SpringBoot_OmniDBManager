package com.pkmprojects.mongodbserver.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-database-name lock registry shared by every service that mutates a
 * database's lifecycle or content (provisioning, restore, bulk import).
 *
 * <p>The guarded operations are check-then-act sequences spanning multiple
 * MongoDB commands, so two concurrent calls for the same name must not
 * interleave: concurrent {@code provision}/{@code delete} calls otherwise
 * produce orphaned metadata or a database whose user was already dropped,
 * concurrent {@code createUser} commands against a brand-new database can
 * lose the user insert entirely while still reporting success (MongoDB does
 * not serialize user creation on a not-yet-existing database), and a
 * {@code restore}/{@code import} racing a {@code delete} can resurrect a
 * just-dropped database as a zombie with no metadata or user. Different
 * database names are never blocked by each other.</p>
 *
 * <p>Entries are intentionally never removed: the set of distinct names is
 * bounded by the databases an admin manages, and removing a lock after use
 * reintroduces a check-then-act race on the removal itself.</p>
 */
@Component
public class DatabaseLockRegistry {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * Runs {@code action} while holding the lock for {@code dbName}.
     */
    public void withLock(String dbName, Runnable action) {
        synchronized (locks.computeIfAbsent(dbName, key -> new Object())) {
            action.run();
        }
    }

    /**
     * Runs {@code action} while holding the lock for {@code dbName}.
     */
    public <T> T withLock(String dbName, Supplier<T> action) {
        synchronized (locks.computeIfAbsent(dbName, key -> new Object())) {
            return action.get();
        }
    }

    public void withLock(com.pkmprojects.mongodbserver.model.DatabaseEngineType engine, String dbName, Runnable action) {
        withLock(engine.name() + ":" + dbName, action);
    }

    public <T> T withLock(com.pkmprojects.mongodbserver.model.DatabaseEngineType engine, String dbName, Supplier<T> action) {
        return withLock(engine.name() + ":" + dbName, action);
    }
}
