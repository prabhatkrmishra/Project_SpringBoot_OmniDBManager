package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.PostgresDatabaseStats;
import com.pkmprojects.mongodbserver.dto.TableStats;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
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

/**
 * Read-only PostgreSQL statistics from pg_stat_user_tables + pg_total_relation_size.
 */
@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresStatisticsService {

    private static final Logger log = LoggerFactory.getLogger(PostgresStatisticsService.class);

    private final PostgresDatabaseRepository postgresRepository;
    private final DatabaseNameValidator nameValidator;

    public PostgresStatisticsService(@Autowired(required = false) PostgresDatabaseRepository postgresRepository,
                                     DatabaseNameValidator nameValidator) {
        this.postgresRepository = postgresRepository;
        this.nameValidator = nameValidator;
    }

    public PostgresDatabaseStats getDatabaseStats(String dbName) {
        nameValidator.validatePostgresDatabaseName(dbName);
        requireDatabase(dbName);

        List<String> tableNames;
        try {
            tableNames = postgresRepository.listTables(dbName);
        } catch (Exception e) {
            log.warn("Could not list tables for {}", dbName, e);
            throw new ProvisioningException("Could not read statistics for database '" + dbName + "'", e);
        }

        List<TableStats> tables = parallelTableStats(dbName, tableNames);

        long totalRows = tables.stream().mapToLong(TableStats::rowCount).sum();
        long totalSize = tables.stream().mapToLong(TableStats::sizeBytes).sum();

        return new PostgresDatabaseStats(dbName, tableNames.size(), totalRows, totalSize, tables);
    }

    private List<TableStats> parallelTableStats(String dbName, List<String> tableNames) {
        if (tableNames.size() <= 1) {
            return tableNames.stream().map(name -> tableStats(dbName, name)).toList();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<TableStats>> futures = tableNames.stream()
                    .map(name -> CompletableFuture.supplyAsync(() -> tableStats(dbName, name), executor))
                    .toList();
            return futures.stream().map(PostgresStatisticsService::joinUnwrapping).toList();
        }
    }

    private TableStats tableStats(String dbName, String tableName) {
        long rowCount = 0;
        long sizeBytes = 0;
        long liveTuples = 0;
        long deadTuples = 0;
        String lastVacuum = null;
        String lastAnalyze = null;
        try {
            rowCount = postgresRepository.countRows(dbName, tableName);
        } catch (Exception e) {
            log.warn("Could not count rows for {}.{}", dbName, tableName, e);
        }
        try {
            sizeBytes = postgresRepository.getTableSizeQualified(dbName, tableName);
        } catch (Exception e) {
            log.warn("Could not read size for {}.{}", dbName, tableName, e);
        }
        try {
            Map<String, Object> stat = postgresRepository.getTableStats(dbName, tableName);
            liveTuples = toLong(stat.get("n_live_tup"));
            deadTuples = toLong(stat.get("n_dead_tup"));
            Object lv = stat.get("last_vacuum");
            if (lv == null) lv = stat.get("last_autovacuum");
            Object la = stat.get("last_analyze");
            if (la == null) la = stat.get("last_autoanalyze");
            lastVacuum = lv != null ? lv.toString() : null;
            lastAnalyze = la != null ? la.toString() : null;
        } catch (Exception e) {
            log.warn("Could not read pg_stat for {}.{}", dbName, tableName, e);
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
            if (!postgresRepository.databaseExists(dbName)) {
                throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
            }
        } catch (DatabaseNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not check existence of database {}", dbName, e);
            throw new ProvisioningException("Could not check database '" + dbName + "'", e);
        }
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
