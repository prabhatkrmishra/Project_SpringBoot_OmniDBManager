package com.pkmprojects.mongodbserver.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PgbouncerAdminServiceTest {
    @Test
    void adminConsoleUrlSuppressesDriverSetProbe() {
        // pgjdbc sends `SET extra_float_digits` on connect unless told not
        // to; the pgbouncer console db rejects it ("SET failed", proven live).
        var svc = new PgbouncerAdminService(
                new com.pkmprojects.mongodbserver.config.PgbouncerProperties(
                        6432, 6432, "transaction", 1000, 5, 2, 3, 10, "a", "s", "p"));
        assertThat(svc.adminJdbcUrl()).contains("assumeMinServerVersion=9.0");
    }


    @Test
    void quotingEscapesDoubleQuotesSoOneDbCannotEscapeItsIdentifier() {
        assertThat(PgbouncerAdminService.quoted("myapp")).isEqualTo("\"myapp\"");
        assertThat(PgbouncerAdminService.quoted("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(PgbouncerAdminService.quoted("x\"; KILL *; --")).isEqualTo("\"x\"\"; KILL *; --\"");
    }
}
