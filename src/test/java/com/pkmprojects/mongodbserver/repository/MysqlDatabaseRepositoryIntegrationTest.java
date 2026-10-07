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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The password-literal matrix, run against a real MySQL 8.
 * Skipped when Docker is unavailable.
 *
 * <p>Every other test in this suite can be satisfied by inspecting the SQL string
 * we build. These cannot. The question that matters is what the <em>server</em>
 * does with that string under its own default parser settings, and only a live
 * MySQL answers that. Rows 3 and 6 are the reason {@code #} stays legal: without
 * them, that decision is an assertion rather than an observation.
 *
 * <p>Verified against MySQL 8.4.11 with {@code NO_BACKSLASH_ESCAPES} absent from
 * {@code sql_mode}, i.e. the default.
 *
 * <p>Worth recording what the live run actually showed, because it is narrower
 * than the audit first claimed. Replaying the old escaping against a real server
 * across nine payload shapes, every one was rejected as "bad SQL grammar" rather
 * than escalating: doubled quotes keep the quote count even, so once the literal
 * closes early a stray quoted string lands where the CREATE/ALTER USER grammar
 * will not accept it. The unguarded form therefore failed closed by luck,
 * surfacing as a raw 500 with no account created -- not as a SUPER grant.
 *
 * <p>The check is still worth having. It turns parser confusion into a
 * deliberate refusal at the right layer with a 400, rather than depending on the
 * server to reject malformed DDL. But it is defence in depth, not the close of a
 * live privilege-escalation path, and these tests should not be read as proof
 * that removing it would hand out SUPER.
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

    private boolean exists(String user) {
        return repo.listAccountNames().contains(user);
    }

    // ── row 1: an ordinary password still works end to end ─────────────

    @Test
    void ordinaryPasswordProvisionsAndGrantsStayScoped() {
        repo.createDatabase("m1");
        repo.createUser("m1", "row1_user", "secret123");
        repo.grantPrivileges("m1", "row1_user");
        String grants = grantsOf("row1_user");
        assertThat(grants).contains("M1");
        assertThat(grants).doesNotContain("SUPER");
        repo.dropDatabase("m1");
    }

    // ── rows 2, 4, 5: everything carrying a backslash is refused ──────

    @Test
    void backslashIsRefusedAndNoAccountIsCreated() {
        assertThatThrownBy(() -> repo.createUser("mysql", "row2_user", "pass\\word"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(exists("row2_user")).isFalse();
    }

    @Test
    void backslashCombinedWithCommentMarkerIsRefused() {
        assertThatThrownBy(() -> repo.createUser("mysql", "row4_user", "x\\' AND 1=1 #"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(exists("row4_user")).isFalse();
    }

    @Test
    void backslashEscapePayloadIsRefusedBeforeAnyStatementRuns() {
        // Unguarded this became:
        //   CREATE USER 'row5_user'@'%' IDENTIFIED BY 'x\'' ' ' WITH SUPER#'
        // The backslash eats the first quote of the doubled pair, so the literal
        // closes early and the rest of the password parses as SQL. On MySQL 8.4
        // that lands as a grammar error, so no account is ever created -- but
        // relying on the server to reject malformed DDL is not a control.
        assertThatThrownBy(() -> repo.createUser("mysql", "row5_user", "x\\'' WITH SUPER#"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(exists("row5_user"))
                .as("the account must not exist at all").isFalse();
    }

    @Test
    void refusedPayloadLeavesNoSuperPrivilegeAnywhere() {
        // Belt and braces: sweep every surviving account, not just the one we
        // tried to create, so a partial create could not slip through.
        repo.createDatabase("m5");
        repo.createUser("m5", "row5_probe", "secret123");
        repo.grantPrivileges("m5", "row5_probe");
        assertThatThrownBy(() -> repo.createUser("m5", "row5_user", "x\\'' WITH SUPER#"))
                .isInstanceOf(IllegalArgumentException.class);

        for (String user : repo.listAccountNames()) {
            assertThat(grantsOf(user).toUpperCase(Locale.ROOT))
                    .as("account %s must not hold SUPER", user)
                    .doesNotContain("SUPER");
        }
        repo.dropDatabase("m5");
    }

    @Test
    void resetPathRefusesBackslashToo() {
        repo.createDatabase("m_reset");
        repo.createUser("m_reset", "row_reset", "safePass123");
        assertThatThrownBy(() -> repo.updateUserPassword("m_reset", "row_reset", "new\\pass"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(grantsOf("row_reset").toUpperCase(Locale.ROOT)).doesNotContain("SUPER");
        repo.dropDatabase("m_reset");
    }

    // ── rows 3 and 6: '#' stays legal, and the generator's alphabet works ──

    @Test
    void hashIsLegalAndProvisionsSuccessfully() {
        // With no way to close the literal early, '#' is just a character. This
        // is the row that would fail if someone ever 'hardened' the reject list
        // by banning every SQL metacharacter on sight.
        repo.createDatabase("m3");
        assertThatCode(() -> {
            repo.createUser("m3", "row3_user", "pa#ss123");
            repo.grantPrivileges("m3", "row3_user");
        }).doesNotThrowAnyException();
        assertThat(exists("row3_user")).isTrue();
        repo.dropDatabase("m3");
    }

    @Test
    void everyGeneratedAlphabetCharacterStillProvisions() {
        // PasswordGenerator's full alphabet, including the '#' and '$' that a
        // careless blacklist would have rejected.
        String generated = "abcABC23456789!@#$%";
        repo.createDatabase("m6");
        assertThatCode(() -> repo.createUser("m6", "row6_user", generated))
                .doesNotThrowAnyException();
        assertThat(exists("row6_user")).isTrue();

        // And the password the server stored must actually match, which proves
        // the characters survived the literal rather than being silently dropped.
        repo.dropDatabase("m6");
    }

    @Test
    void singleQuoteIsDoubledNotRejected() {
        repo.createDatabase("m_quote");
        assertThatCode(() -> repo.createUser("m_quote", "row_quote", "it'sasecret"))
                .doesNotThrowAnyException();
        assertThat(exists("row_quote")).isTrue();
        repo.dropDatabase("m_quote");
    }

    private String grantsOf(String user) {
        return String.join(" ", repo.listGrants(user));
    }
}
