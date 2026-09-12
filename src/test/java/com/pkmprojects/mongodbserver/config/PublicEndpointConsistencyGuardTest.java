package com.pkmprojects.mongodbserver.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class PublicEndpointConsistencyGuardTest {
    private static PgbouncerProperties props() {
        return new PgbouncerProperties(6432, 6432, "transaction", 1000, 5, 2, 3, 10, "a", "s", "p");
    }

    @Test
    void disabledProxyIsNoop() {
        var guard = new PublicEndpointConsistencyGuard(
                new DatabaseProxyProperties(false, 15432, "", ""), props());
        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void enabledProxyRequiresDistinctHostnames() {
        var guard = new PublicEndpointConsistencyGuard(
                new DatabaseProxyProperties(true, 15432, "db.example.com", "db.example.com"), props());
        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void enabledProxyRequiresBothHostnames() {
        var guard = new PublicEndpointConsistencyGuard(
                new DatabaseProxyProperties(true, 15432, "db.example.com", ""), props());
        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonTransactionPoolModeRejected() {
        var guard = new PublicEndpointConsistencyGuard(
                new DatabaseProxyProperties(false, 15432, "", ""),
                new PgbouncerProperties(6432, 6432, "session", 1000, 5, 2, 3, 10, "a", "s", "p"));
        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }
}
