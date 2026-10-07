package com.pkmprojects.mongodbserver.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link MysqlDatabaseRepository} against a real MySQL 8.
 * Skipped when Docker is unavailable.
 *
 * <p>The escape tests here are the reason this class exists. Every other test in
 * the suite can be satisfied by inspecting the SQL string we build, but the
 * question that matters for the password path is what the <em>server</em> does
 * with that string under its real default parser settings. Only a live MySQL
 * answers that.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MysqlDatabaseRepositoryIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withUsername("root")
            .withPassword("root");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.mongo.enabled", () -> "false");
        r.add("app.postgres.enabled", () -> "false");
        r.add("app.mysql.enabled", () -> "true");
        r.add("app.mysql.uri", () -> "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/mysql");
        r.add("MYSQL_ROOT_PASSWORD", () -> "root");
        r.add("app.admin.username", () -> "admin");
        r.add("app.admin.password", () -> "admin");
    }

    @Autowired
    private MysqlDatabaseRepository repo;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    private void cleanup() {
        for (String db : List.copyOf(repo.listDatabaseNames())) {
            repo.dropDatabase(db);
        }
        for (String user : List.copyOf(repo.listAccountNames())) {
            try {
                repo.dropUser("mysql", user);
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    // ── the exploit this suite exists to prove is closed ───────────────

    @Test
    void privilegeEscalationPayloadNeverReachesTheServer() {
        // The exact payload from the audit. Before the backslash ban this became
        //   ALTER USER 'esc_tester'@'%' IDENTIFIED BY 'x\'''' WITH SUPER#'
        // and MySQL granted SUPER cluster-wide, letting one tenant read
        // mysql.user (every password hash) and attach to every other tenant's
        // database.
        assertThatThrownBy(() -> repo.createUser("mysql", "esc_tester", "x\\'' WITH SUPER#"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.listAccountNames()).doesNotContain("esc_tester");
    }

    @Test
    void backslashIsRejectedOnTheResetPathToo() {
        repo.createDatabase("esc_db");
        repo.createUser("esc_db", "esc_reset", "safePass123");
        try {
            assertThatThrownBy(() -> repo.updateUserPassword("esc_db", "esc_reset", "new\\pass"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(grantsFor("esc_reset")).doesNotContain("SUPER");
        } finally {
            repo.dropDatabase("esc_db");
        }
    }

    // ── the happy path still works against a real server ───────────────

    @Test
    void ordinaryPasswordStillProvisionsAndGrantsStayScoped() {
        repo.createDatabase("esc_ok");
        repo.createUser("esc_ok", "esc_ok_user", "sa#fe'Pass123");
        repo.grantPrivileges("esc_ok", "esc_ok_user");
        try {
            String grants = String.join(" ", grantsFor("esc_ok_user")).toUpperCase(Locale.ROOT);
            assertThat(grants).contains("ESC_OK");
            // A single quote and a hash are doubled/inert, not rejected.
            assertThat(grants).doesNotContain("SUPER");
        } finally {
            repo.dropDatabase("esc_ok");
        }
    }

    private List<String> grantsFor(String user) {
        return repo.listGrants(user);
    }
}
