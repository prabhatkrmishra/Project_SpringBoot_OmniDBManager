package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.MysqlDatabaseStats;
import com.pkmprojects.mongodbserver.dto.TableStats;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlStatisticsService {

    private static final Logger log = LoggerFactory.getLogger(MysqlStatisticsService.class);

    private final MysqlDatabaseRepository mysqlRepository;
    private final DatabaseNameValidator nameValidator;

    public MysqlStatisticsService(@Autowired(required = false) MysqlDatabaseRepository mysqlRepository,
                                  DatabaseNameValidator nameValidator) {
        this.mysqlRepository = mysqlRepository;
        this.nameValidator = nameValidator;
    }

    public MysqlDatabaseStats getDatabaseStats(String dbName) {
        nameValidator.validateMysqlDatabaseName(dbName);
        requireDatabase(dbName);

        List<String> tableNames;
        try {
            tableNames = mysqlRepository.listTables(dbName);
        } catch (Exception e) {
            log.warn("Could not list tables for {}", dbName, e);
            throw new ProvisioningException("Could not read statistics for database '" + dbName + "'", e);
        }

        List<TableStats> tables = parallelTableStats(dbName, tableNames);

        long totalRows = tables.stream().mapToLong(TableStats::rowCount).sum();
        long totalSize = tables.stream().mapToLong(TableStats::sizeBytes).sum();

        return new MysqlDatabaseStats(dbName, tableNames.size(), totalRows, totalSize, tables);
    }

    private List<TableStats> parallelTableStats(String dbName, List<String> tableNames) {
        if (tableNames.size() <= 1) {
            return tableNames.stream().map(name -> tableStats(dbName, name)).toList();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<TableStats>> futures = tableNames.stream()
                    .map(name -> CompletableFuture.supplyAsync(() -> tableStats(dbName, name), executor))
                    .toList();
            return futures.stream().map(MysqlStatisticsService::joinUnwrapping).toList();
        }
    }

    private TableStats tableStats(String dbName, String tableName) {
        long rowCount = 0;
        long sizeBytes = 0;
        try {
            rowCount = mysqlRepository.countRows(dbName, tableName);
        } catch (Exception e) {
            log.warn("Could not count rows for {}.{}", dbName, tableName, e);
        }
        try {
            sizeBytes = mysqlRepository.getTableSize(dbName, tableName);
        } catch (Exception e) {
            log.warn("Could not read size for {}.{}", dbName, tableName, e);
        }
        long liveTuples = rowCount;
        long deadTuples = 0;
        String lastVacuum = null;
        String lastAnalyze = null;
        try {
            Map<String, Object> stat = mysqlRepository.getTableStats(dbName, tableName);
            Object tr = stat.get("table_rows");
            if (tr instanceof Number n) liveTuples = n.longValue();
            Object dl = stat.get("data_length");
            Object il = stat.get("index_length");
            long dlVal = dl instanceof Number n ? n.longValue() : 0L;
            long ilVal = il instanceof Number n ? n.longValue() : 0L;
            sizeBytes = dlVal + ilVal;
        } catch (Exception e) {
            log.warn("Could not read information_schema for {}.{}", dbName, tableName, e);
        }
        return new TableStats(tableName, rowCount, sizeBytes, liveTuples, deadTuples, lastVacuum, lastAnalyze);
    }

    private static <T> T joinUnwrapping(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private void requireDatabase(String dbName) {
        try {
            if (!mysqlRepository.databaseExists(dbName)) {
                throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
            }
        } catch (DatabaseNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not check existence of database {}", dbName, e);
            throw new ProvisioningException("Could not check database '" + dbName + "'", e);
        }
    }
}
