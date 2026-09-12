package com.pkmprojects.mongodbserver.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PgbouncerAdminServiceTest {
    @Test
    void quotingEscapesDoubleQuotesSoOneDbCannotEscapeItsIdentifier() {
        assertThat(PgbouncerAdminService.quoted("myapp")).isEqualTo("\"myapp\"");
        assertThat(PgbouncerAdminService.quoted("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(PgbouncerAdminService.quoted("x\"; KILL *; --")).isEqualTo("\"x\"\"; KILL *; --\"");
    }
}
