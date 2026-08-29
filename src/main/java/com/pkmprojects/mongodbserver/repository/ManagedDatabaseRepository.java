package com.pkmprojects.mongodbserver.repository;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;
import java.util.Optional;

/**
 * Spring Data repository for provisioning metadata (stored in the {@code mongodb_admin}
 * database). Composite identity is {@code engineType + ":" + dbName} so the same
 * name can exist in both engines. Legacy {@code findByDbName} methods remain for
 * backward compat and legacy redirects.
 */
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public interface ManagedDatabaseRepository extends MongoRepository<ManagedDatabase, String> {

    /**
     * @return the provisioning metadata for {@code dbName}, if provisioned (first match if same name in both engines)
     */
    Optional<ManagedDatabase> findByDbName(String dbName);

    boolean existsByDbName(String dbName);

    void deleteByDbName(String dbName);

    Optional<ManagedDatabase> findByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName);

    boolean existsByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName);

    void deleteByEngineTypeAndDbName(DatabaseEngineType engineType, String dbName);

    List<ManagedDatabase> findAllByEngineType(DatabaseEngineType engineType);

    long countByEngineType(DatabaseEngineType engineType);

    List<ManagedDatabase> findByDbNameOrderByEngineType(String dbName);
}
