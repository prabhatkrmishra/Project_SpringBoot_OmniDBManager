package com.pkmprojects.mongodbserver.store.inmemory;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fallback for provisioning metadata when MongoDB is disabled.
 * Data lives only for the process lifetime — exactly the same
 * ephemerality as the existing Mongo fallback described in the
 * all-hidden / no-engine case.
 */
@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryManagedDatabaseStore implements ManagedDatabaseStore {

    private final Map<String, ManagedDatabase> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<ManagedDatabase> findByDbName(String dbName) {
        return byId.values().stream().filter(m -> m.getDbName().equals(dbName)).findFirst();
    }

    @Override
    public boolean existsByDbName(String dbName) {
        return byId.values().stream().anyMatch(m -> m.getDbName().equals(dbName));
    }

    @Override
    public void deleteByDbName(String dbName) {
        byId.entrySet().removeIf(e -> e.getValue().getDbName().equals(dbName));
    }

    @Override
    public Optional<ManagedDatabase> findByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName) {
        return Optional.ofNullable(byId.get(engineType.name() + ":" + dbName));
    }

    @Override
    public boolean existsByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName) {
        return byId.containsKey(engineType.name() + ":" + dbName);
    }

    @Override
    public void deleteByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName) {
        byId.remove(engineType.name() + ":" + dbName);
    }

    @Override
    public List<ManagedDatabase> findAllByEngineType(DatabaseEngineType engineType) {
        return byId.values().stream().filter(m -> m.getEngineType() == engineType).toList();
    }

    @Override
    public long countByEngineType(DatabaseEngineType engineType) {
        return byId.values().stream().filter(m -> m.getEngineType() == engineType).count();
    }

    @Override
    public List<ManagedDatabase> findByDbNameOrderByEngineType(String dbName) {
        return byId.values().stream()
                .filter(m -> m.getDbName().equals(dbName))
                .sorted(Comparator.comparing(m -> m.getEngineType().name()))
                .toList();
    }

    @Override
    public List<ManagedDatabase> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public ManagedDatabase save(ManagedDatabase entity) {
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public long count() {
        return byId.size();
    }

    @Override
    public Optional<ManagedDatabase> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public void deleteById(String id) {
        byId.remove(id);
    }
}
