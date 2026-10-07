package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * MySQL half of the password rules that {@code MysqlDatabaseRepository} also
 * enforces at SQL-construction time. The point of these tests is the
 * <em>ordering</em> they guarantee: a hostile password is refused here, before
 * any database exists and before any SQL string is built, so the caller gets a
 * 400 rather than a partially-provisioned tenant.
 */
class DatabaseNameValidatorMysqlTest {

    private final DatabaseNameValidator validator = new DatabaseNameValidator();

    // ── accepted ──────────────────────────────────────────────────────

    @Test
    void acceptsOrdinaryPasswords() {
        assertDoesNotThrow(() -> validator.validateMysqlPassword("secret123"));
        assertDoesNotThrow(() -> validator.validateMysqlPassword("correct horse battery"));
    }

    @Test
    void acceptsSingleQuoteBecauseItIsDoubledNotRejected() {
        assertDoesNotThrow(() -> validator.validateMysqlPassword("it'sasecret"));
    }

    @Test
    void acceptsHashBecauseTheLiteralCannotBeClosedEarly() {
        // PasswordGenerator emits '#' in roughly one password in sixty. Banning it
        // here would reject strong generated passwords for no security gain: once
        // backslash cannot appear there is no way to terminate the literal early,
        // which is what makes '#' (MySQL's line comment) inert.
        assertDoesNotThrow(() -> validator.validateMysqlPassword("pa#ss123"));
    }

    @Test
    void acceptsGeneratedAlphabetCharacters() {
        // Every distinct character PasswordGenerator can emit.
        assertDoesNotThrow(() -> validator.validateMysqlPassword("abcABC23456789!@#$%"));
    }

    // ── rejected ──────────────────────────────────────────────────────

    @Test
    void rejectsBackslash() {
        // MySQL treats '\' as an escape character inside '...' by default, so a
        // backslash before a doubled quote terminates the literal early and the
        // rest of the password parses as SQL. This is the character that made
        // quote-doubling alone insufficient.
        assertThatThrownBy(() -> validator.validateMysqlPassword("pass\\word"))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("backslash");
    }

    @Test
    void rejectsSemicolon() {
        assertThatThrownBy(() -> validator.validateMysqlPassword("pass;word"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsDoubleDash() {
        assertThatThrownBy(() -> validator.validateMysqlPassword("pass--word"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsBlockCommentOpen() {
        assertThatThrownBy(() -> validator.validateMysqlPassword("pass/*word"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsBlockCommentClose() {
        assertThatThrownBy(() -> validator.validateMysqlPassword("pass*/word"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsThePrivilegeEscalationPayload() {
        // The payload that turns a tenant-scoped ALTER USER into a cluster-wide
        // SUPER grant: the backslash escapes the first quote of the doubled pair,
        // the second quote closes the literal, and '#' comments out the template's
        // own closing quote.
        assertThatThrownBy(() -> validator.validateMysqlPassword("x\\'' WITH SUPER#"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void rejectsCombinationOfBackslashAndStatementTerminator() {
        assertThatThrownBy(() -> validator.validateMysqlPassword("x\\'; CREATE ROLE evil SUPER; --"))
                .isInstanceOf(NameNotAllowedException.class);
    }

    // ── unchanged pre-existing rules ───────────────────────────────────

    @Test
    void stillRejectsTooShort() {
        assertThatThrownBy(() -> validator.validateMysqlPassword("short12"))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("at least 8");
    }

    @Test
    void stillRejectsTooLong() {
        assertThatThrownBy(() -> validator.validateMysqlPassword("a".repeat(129)))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("at most 128");
    }

    @Test
    void skipsChecksForBlankPassword() {
        // Blank means "generate one for me"; the generator's alphabet contains no
        // disallowed character, so there is nothing to reject.
        assertDoesNotThrow(() -> validator.validateMysqlPassword(""));
        assertDoesNotThrow(() -> validator.validateMysqlPassword("   "));
        assertDoesNotThrow(() -> validator.validateMysqlPassword(null));
    }

    // ── the two layers cannot drift ────────────────────────────────────

    @Test
    void agreesExactlyWithTheRepositoryPredicate() {
        String[] samples = {
                "secret123", "it'sasecret", "pa#ss123", "abcABC23456789!@#$%",
                "pass\\word", "pass;word", "pass--word", "pass/*word", "pass*/word",
                "x\\'' WITH SUPER#", "x\\'; CREATE ROLE evil SUPER; --",
        };
        for (String sample : samples) {
            boolean validatorRejects = !accepts(() -> validator.validateMysqlPassword(sample));
            boolean repositoryRejects = !com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository
                            .isSafeForMysqlPasswordLiteral(sample);
            org.junit.jupiter.api.Assertions.assertEquals(repositoryRejects, validatorRejects,
                    () -> "layers disagree about: " + sample);
        }
    }

    private boolean accepts(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException expected) {
            return false;
        }
    }
}