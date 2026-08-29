package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.ProvisioningException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Per-database-name lock registry shared by every service that mutates a
 * database's lifecycle or content (provisioning, restore, bulk import).
 *
 * <p>The guarded operations are check-then-act sequences spanning multiple
 * commands, so two concurrent calls for the same name must not
 * interleave. Different database names are never blocked by each other.</p>
 *
 * <p>Uses {@link ReentrantLock} instead of {@code synchronized} so virtual
 * threads (used by StatisticsService fan-out) do not pin carrier threads
 * (JDK 21+ pinning hazard). Locks are held with a bounded wait (30s) to
 * avoid indefinite blocking when PG/MySQL hang.</p>
 *
 * <p>Entries are intentionally never removed eagerly: the set of distinct
 * names is bounded by the databases an admin manages, and removing a lock
 * after use reintroduces a check-then-act race on the removal itself.
 * A background eviction could be added via Caffeine if churn grows.</p>
 */
@Component
public class DatabaseLockRegistry {

    private static final long LOCK_TIMEOUT_SECONDS = 30;

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Runs {@code action} while holding the lock for {@code dbName}.
     */
    public void withLock(String dbName, Runnable action) {
        ReentrantLock lock = locks.computeIfAbsent(dbName, key -> new ReentrantLock());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException("Interrupted while waiting for lock on '" + dbName + "'", e);
        }
        if (!acquired) {
            throw new ProvisioningException("Timed out waiting for lock on '" + dbName + "' — another operation is still in progress");
        }
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code action} while holding the lock for {@code dbName}.
     */
    public <T> T withLock(String dbName, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(dbName, key -> new ReentrantLock());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException("Interrupted while waiting for lock on '" + dbName + "'", e);
        }
        if (!acquired) {
            throw new ProvisioningException("Timed out waiting for lock on '" + dbName + "' — another operation is still in progress");
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(com.pkmprojects.mongodbserver.model.DatabaseEngineType engine, String dbName, Runnable action) {
        withLock(engine.name() + ":" + dbName, action);
    }

    public <T> T withLock(com.pkmprojects.mongodbserver.model.DatabaseEngineType engine, String dbName, Supplier<T> action) {
        return withLock(engine.name() + ":" + dbName, action);
    }
}
