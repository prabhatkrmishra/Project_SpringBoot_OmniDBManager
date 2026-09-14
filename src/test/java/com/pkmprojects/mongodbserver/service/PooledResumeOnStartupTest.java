package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S-09 P2: a crash between PgBouncer PAUSE and RESUME must not wedge pooled
 * clients forever — startup best-effort RESUMEs every metadata-pooled PG
 * database, and never throws.
 */
@ExtendWith(MockitoExtension.class)
class PooledResumeOnStartupTest {

    @Mock
    private ManagedDatabaseStore store;

    @Mock
    private PgbouncerAdminService admin;

    private static ManagedDatabase md(String db, DatabaseEngineType engine, boolean pooled) {
        ManagedDatabase m = new ManagedDatabase(db, engine, "u_" + db, List.of(), Instant.now(), Instant.now(), null);
        m.setPooled(pooled);
        return m;
    }

    @Test
    void resumesOnlyPooledPostgresDatabases() {
        when(store.findAll()).thenReturn(List.of(
                md("a", DatabaseEngineType.POSTGRES, true),
                md("b", DatabaseEngineType.POSTGRES, false),
                md("c", DatabaseEngineType.MONGO, false),
                md("d", DatabaseEngineType.POSTGRES, true)));
        new PooledResumeOnStartup(store, admin).run(null);

        verify(admin).resumeDb("a");
        verify(admin).resumeDb("d");
        verify(admin, never()).resumeDb("b");
        verify(admin, never()).resumeDb("c");
    }

    @Test
    void emptyStoreResumesNothing() {
        when(store.findAll()).thenReturn(List.of());
        new PooledResumeOnStartup(store, admin).run(null);

        verify(admin, never()).resumeDb(anyString());
    }

    @Test
    void storeFailureNeverBreaksStartup() {
        when(store.findAll()).thenThrow(new RuntimeException("mongo down"));
        assertThatCode(() -> new PooledResumeOnStartup(store, admin).run(null))
                .doesNotThrowAnyException();
        verify(admin, never()).resumeDb(anyString());
    }
}
