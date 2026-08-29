package com.pkmprojects.mongodbserver.store.mongo;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoManagedDatabaseStore implements ManagedDatabaseStore {

    private final ManagedDatabaseRepository delegate;

    public MongoManagedDatabaseStore(ManagedDatabaseRepository delegate) {
        this.delegate = delegate;
    }

    @Override public Optional<ManagedDatabase> findByDbName(String dbName) { return delegate.findByDbName(dbName); }
    @Override public boolean existsByDbName(String dbName) { return delegate.existsByDbName(dbName); }
    @Override public void deleteByDbName(String dbName) { delegate.deleteByDbName(dbName); }
    @Override public Optional<ManagedDatabase> findByEngineTypeAndDbName(DatabaseEngineType t, String n) { return delegate.findByEngineTypeAndDbName(t, n); }
    @Override public boolean existsByEngineTypeAndDbName(DatabaseEngineType t, String n) { return delegate.existsByEngineTypeAndDbName(t, n); }
    @Override public void deleteByEngineTypeAndDbName(DatabaseEngineType t, String n) { delegate.deleteByEngineTypeAndDbName(t, n); }
    @Override public List<ManagedDatabase> findAllByEngineType(DatabaseEngineType t) { return delegate.findAllByEngineType(t); }
    @Override public long countByEngineType(DatabaseEngineType t) { return delegate.countByEngineType(t); }
    @Override public List<ManagedDatabase> findByDbNameOrderByEngineType(String n) { return delegate.findByDbNameOrderByEngineType(n); }
    @Override public List<ManagedDatabase> findAll() { return delegate.findAll(); }
    @Override public ManagedDatabase save(ManagedDatabase e) { return delegate.save(e); }
    @Override public long count() { return delegate.count(); }
    @Override public Optional<ManagedDatabase> findById(String id) { return delegate.findById(id); }
    @Override public void deleteById(String id) { delegate.deleteById(id); }
}
