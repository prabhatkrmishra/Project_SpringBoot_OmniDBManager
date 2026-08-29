package com.pkmprojects.mongodbserver.store;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction over provisioning metadata so the store can be Mongo-backed
 * when {@code app.mongo.enabled=true} or in-memory when Mongo is disabled.
 */
public interface ManagedDatabaseStore {

    Optional<ManagedDatabase> findByDbName(String dbName);

    boolean existsByDbName(String dbName);

    void deleteByDbName(String dbName);

    Optional<ManagedDatabase> findByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName);

    boolean existsByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName);

    void deleteByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName);

    List<ManagedDatabase> findAllByEngineType(DatabaseEngineType engineType);

    long countByEngineType(DatabaseEngineType engineType);

    List<ManagedDatabase> findByDbNameOrderByEngineType(String dbName);

    List<ManagedDatabase> findAll();

    ManagedDatabase save(ManagedDatabase entity);

    long count();

    Optional<ManagedDatabase> findById(String id);

    void deleteById(String id);
}
