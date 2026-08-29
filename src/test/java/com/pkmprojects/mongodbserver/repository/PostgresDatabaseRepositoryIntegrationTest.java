package com.pkmprojects.mongodbserver.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PostgresDatabaseRepository} against a real
 * PostgreSQL 18. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresDatabaseRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6-alpine"))
            .withUsername("root")
            .withPassword("root")
            .withDatabaseName("postgres");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.mongo.enabled", () -> "false");
        r.add("app.mysql.enabled", () -> "false");
        r.add("app.postgres.enabled", () -> "true");
        r.add("app.postgres.uri", () -> "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/postgres");
        r.add("POSTGRES_ROOT_USER", () -> "root");
        r.add("POSTGRES_ROOT_PASSWORD", () -> "root");
        r.add("app.admin.username", () -> "admin");
        r.add("app.admin.password", () -> "admin");
    }

    @Autowired
    private PostgresDatabaseRepository repo;

    @BeforeEach
    void setUp() {
        cleanupTestDatabases();
    }

    @AfterEach
    void cleanUp() {
        cleanupTestDatabases();
    }

    private void cleanupTestDatabases() {
        for (String db : List.copyOf(repo.listDatabaseNames())) {
            if (!db.equals("postgres")) {
                try { repo.dropDatabase(db); } catch (Exception ignored) {}
                // roles survive DB drop — clean up matching role
                try { repo.dropUser(db, db + "_user"); } catch (Exception ignored) {}
                try { repo.dropUser(db, "testuser"); } catch (Exception ignored) {}
            }
        }
    }

    @Test
    void createAndDropDatabase() {
        assertThat(repo.databaseExists("testdb")).isFalse();
        repo.createDatabase("testdb", "root");
        assertThat(repo.databaseExists("testdb")).isTrue();
        assertThat(repo.listDatabaseNames()).contains("testdb");
        repo.dropDatabase("testdb");
        assertThat(repo.databaseExists("testdb")).isFalse();
    }

    @Test
    void createUserAndGrantPrivileges() {
        repo.createDatabase("testdb", "root");
        repo.createUser("testdb", "testuser", "secret1234");
        repo.grantPrivileges("testdb", "testuser");
        List<String> users = repo.getUsers("testdb");
        assertThat(users).contains("testuser");
        repo.dropDatabase("testdb");
        repo.dropUser("testdb", "testuser");
    }

    @Test
    void updateUserPassword() {
        repo.createDatabase("testdb", "root");
        repo.createUser("testdb", "testuser", "firstpass1");
        repo.grantPrivileges("testdb", "testuser");
        repo.updateUserPassword("testdb", "testuser", "secondpass2");
        // no exception = success; verify user still has connect
        assertThat(repo.getUsers("testdb")).contains("testuser");
        repo.dropDatabase("testdb");
        repo.dropUser("testdb", "testuser");
    }

    @Test
    void tableLifecycle() {
        repo.createDatabase("testdb", "root");
        repo.createUser("testdb", "testuser", "secret1234");
        repo.grantPrivileges("testdb", "testuser");

        assertThat(repo.listTables("testdb")).isEmpty();
        repo.createTable("testdb", "users", List.of("name", "email"));
        assertThat(repo.tableExists("testdb", "users")).isTrue();
        assertThat(repo.listTables("testdb")).contains("users");
        assertThat(repo.getTableColumns("testdb", "users")).containsExactlyInAnyOrder("name", "email");

        repo.insertRow("testdb", "users", Map.of("name", "alice", "email", "alice@example.com"));
        repo.insertRow("testdb", "users", Map.of("name", "bob", "email", "bob@example.com"));
        assertThat(repo.countRows("testdb", "users")).isEqualTo(2);

        List<Map<String, Object>> rows = repo.listRows("testdb", "users", 10, 0);
        assertThat(rows).hasSize(2);

        List<Map<String, Object>> withCtid = repo.listRowsWithCtid("testdb", "users", 10, 0);
        assertThat(withCtid).hasSize(2);
        assertThat(withCtid.get(0)).containsKey("__pg_ctid");
        String ctid = (String) withCtid.get(0).get("__pg_ctid");
        repo.deleteRowByCtid("testdb", "users", ctid);
        assertThat(repo.countRows("testdb", "users")).isEqualTo(1);

        repo.truncateTable("testdb", "users");
        assertThat(repo.countRows("testdb", "users")).isZero();

        repo.dropTable("testdb", "users");
        assertThat(repo.tableExists("testdb", "users")).isFalse();

        repo.dropDatabase("testdb");
        repo.dropUser("testdb", "testuser");
    }

    @Test
    void insertRowsBatch() {
        repo.createDatabase("testdb", "root");
        repo.createUser("testdb", "testuser", "secret1234");
        repo.grantPrivileges("testdb", "testuser");
        repo.createTable("testdb", "items", List.of("name", "value"));

        List<Map<String, Object>> batch = List.of(
                Map.of("name", "a", "value", "1"),
                Map.of("name", "b", "value", "2"),
                Map.of("name", "c", "value", "3")
        );
        repo.insertRows("testdb", "items", List.of("name", "value"), batch);
        assertThat(repo.countRows("testdb", "items")).isEqualTo(3);

        repo.dropDatabase("testdb");
        repo.dropUser("testdb", "testuser");
    }

    @Test
    void getDatabaseSizesAndPingAndVersion() {
        repo.ping();
        assertThat(repo.getVersion()).isNotBlank();
        repo.createDatabase("testdb_size", "root");
        try {
            Map<String, Long> sizes = repo.getDatabaseSizes();
            assertThat(sizes).containsKey("testdb_size");
            assertThat(sizes.get("testdb_size")).isGreaterThanOrEqualTo(0L);
            assertThat(repo.getDatabaseSize("testdb_size")).isGreaterThanOrEqualTo(0L);
        } finally {
            try { repo.dropDatabase("testdb_size"); } catch (Exception ignored) {}
        }
    }

    @Test
    void getPostgresMonitorData() {
        Map<String, Object> data = repo.getPostgresMonitorData();
        assertThat(data).containsKey("connectionCount");
        assertThat(data).containsKey("version");
    }

    @Test
    void executeInDatabaseCreatesTable() {
        repo.createDatabase("testdb", "root");
        repo.executeInDatabase("testdb", "CREATE TABLE public.t1 (id TEXT)");
        assertThat(repo.tableExists("testdb", "t1")).isTrue();
        repo.dropDatabase("testdb");
    }

    @Test
    void getTableStatsAndAllTableStats() {
        repo.createDatabase("testdb", "root");
        repo.createUser("testdb", "testuser", "secret1234");
        repo.grantPrivileges("testdb", "testuser");
        repo.createTable("testdb", "users", List.of("name"));
        repo.insertRow("testdb", "users", Map.of("name", "alice"));

        Map<String, Object> stats = repo.getTableStats("testdb", "users");
        assertThat(stats).containsKey("relname");

        var all = repo.getAllTableStats("testdb");
        assertThat(all).isNotEmpty();

        assertThat(repo.getTableSizeQualified("testdb", "users")).isGreaterThanOrEqualTo(0L);

        repo.dropDatabase("testdb");
        repo.dropUser("testdb", "testuser");
    }
}
