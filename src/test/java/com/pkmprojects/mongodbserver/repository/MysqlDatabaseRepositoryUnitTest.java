package com.pkmprojects.mongodbserver.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MysqlDatabaseRepositoryUnitTest {

    // ── quoteIdentifier ───────────────────────────────────────────────

    @Test
    void quoteIdentifierWrapsInBackticks() {
        assertThat(MysqlDatabaseRepository.quoteIdentifier("myapp")).isEqualTo("`myapp`");
    }

    @Test
    void quoteIdentifierEscapesBackticks() {
        assertThat(MysqlDatabaseRepository.quoteIdentifier("my`app")).isEqualTo("`my``app`");
    }

    // ── quoteUser ─────────────────────────────────────────────────────

    @Test
    void quoteUserWrapsInMysqlUserFormat() {
        assertThat(MysqlDatabaseRepository.quoteUser("bob")).isEqualTo("'bob'@'%'");
    }

    @Test
    void quoteUserEscapesSingleQuotes() {
        assertThat(MysqlDatabaseRepository.quoteUser("o'neil")).isEqualTo("'o''neil'@'%'");
    }

    // ── SQL injection defense ────────────────────────────────────────

    @Test
    void createUserEscapesSingleQuoteInPassword() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        repo.createUser("mydb", "bob", "it'sasecret");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.contains("'it''sasecret'")));
    }

    @Test
    void createUserCreatesWhenNotPresent() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(0);
        repo.createUser("mydb", "bob", "secret123");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.startsWith("CREATE USER 'bob'@'%'")));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.startsWith("ALTER USER")));
    }

    @Test
    void createUserAltersExistingUserInsteadOfFailing() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        // User already exists (orphaned from a prior provisioning whose database was dropped)
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(1);
        repo.createUser("mydb", "bob", "secret123");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.startsWith("ALTER USER 'bob'@'%'") && sql.contains("'secret123'")));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.startsWith("CREATE USER")));
    }

    @Test
    void createUserRejectsPasswordWithSemicolon() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        assertThatThrownBy(() -> repo.createUser("mydb", "bob", "pass;word"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed SQL metacharacters");
    }

    @Test
    void updateUserPasswordEscapesSingleQuote() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        repo.updateUserPassword("mydb", "bob", "new'pass");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.contains("'new''pass'")));
    }

    @Test
    void updateUserPasswordAltersExistingUser() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(1);
        repo.updateUserPassword("mydb", "bob", "secret123");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.startsWith("ALTER USER 'bob'@'%'") && sql.contains("'secret123'")));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.startsWith("CREATE USER")));
    }

    @Test
    void updateUserPasswordRecreatesUserWhenMissing() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        // User dropped out-of-band (e.g. manual cleanup) — reset should recreate it
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(0);
        repo.updateUserPassword("mydb", "bob", "secret123");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.startsWith("CREATE USER 'bob'@'%'") && sql.contains("'secret123'")));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.startsWith("ALTER USER")));
    }

    // ── probeUser (provision-time uniquify) ────────────────────────────

    @Test
    void probeUserReportsOccupiedWhenAccountPresent() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(1);
        assertThat(repo.probeUser("bob")).isEqualTo(ProbeResult.OCCUPIED);
    }

    @Test
    void probeUserReportsFreeWhenAbsent() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(0);
        assertThat(repo.probeUser("bob")).isEqualTo(ProbeResult.FREE);
    }

    @Test
    void probeUserFailsClosedOnProbeFailure() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenThrow(new RuntimeException("connection refused"));
        // Must NOT read as FREE. createUser alters an account it finds already
        // present, so treating an unreachable database as "nobody has this name"
        // silently re-points another tenant's credentials at a password only this
        // tenant knows.
        assertThat(repo.probeUser("bob")).isEqualTo(ProbeResult.UNKNOWN);
    }

    @Test
    void probeUserFailsClosedOnNullCount() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = new MysqlDatabaseRepository(jdbc, "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(Integer.class), org.mockito.Mockito.any()))
                .thenReturn(null);
        assertThat(repo.probeUser("bob")).isEqualTo(ProbeResult.UNKNOWN);
    }

    // ── backslash: the character that defeated quote-doubling ─────────
    //
    // MySQL defaults to NO_BACKSLASH_ESCAPES=OFF, so a backslash inside a quoted
    // literal is an escape character. A backslash placed before a doubled quote
    // swallows the first quote and the second one terminates the literal, turning
    // everything after it into SQL. These assert the rejection happens *before* any
    // SQL reaches the driver, not that the emitted string merely looks escaped.

    private static MysqlDatabaseRepository repoWith(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new MysqlDatabaseRepository(jdbc,
                "jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    }

    @Test
    void createUserRejectsPasswordContainingBackslash() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = repoWith(jdbc);
        assertThatThrownBy(() -> repo.createUser("mydb", "bob", "pass\\word"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed SQL metacharacters");
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
                .execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void createUserRejectsThePrivilegeEscalationPayload() {
        // Unguarded, this reached the driver as:
        //   ALTER USER 'bob'@'%' IDENTIFIED BY 'x\'' ' WITH SUPER#'
        // where the backslash eats the first quote of the doubled pair, the next
        // quote closes the literal, and the trailing # comments out the
        // template's own closing quote -- so the server executes WITH SUPER.
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = repoWith(jdbc);
        assertThatThrownBy(() -> repo.createUser("mydb", "bob", "x\\'' WITH SUPER#"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed SQL metacharacters");
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
                .execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateUserPasswordRejectsBackslash() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = repoWith(jdbc);
        assertThatThrownBy(() -> repo.updateUserPassword("mydb", "bob", "pass\\word"))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
                .execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void stillAcceptsHashAndSingleQuote() {
        // Neither can terminate the literal: '#' is inert while the literal is
        // intact, and a single quote is doubled. Both are legal, and
        // PasswordGenerator emits '#'.
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("pa#ss123")).isTrue();
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("it'sasecret")).isTrue();
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("abcABC23456789!@#$%")).isTrue();
    }

    @Test
    void disallowedPredicateCatchesEveryBannedCharacter() {
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("a\\b")).isFalse();
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("a;b")).isFalse();
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("a--b")).isFalse();
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("a/*b")).isFalse();
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("a*/b")).isFalse();
        assertThat(MysqlDatabaseRepository.isSafeForMysqlPasswordLiteral("a#b")).isTrue();
    }

    @Test
    void createUserStillDoublesSingleQuote() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo = repoWith(jdbc);
        repo.createUser("mydb", "bob", "it'sasecret");
        verify(jdbc).execute((String) org.mockito.ArgumentMatchers.argThat((String sql) -> sql.contains("'it''sasecret'")));
    }
}
