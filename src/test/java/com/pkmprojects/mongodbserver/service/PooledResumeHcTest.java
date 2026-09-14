package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.store.ManagedDatabaseStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-14: startup RESUME iterates standard then HC per pooled DB, never throws. */
@ExtendWith(MockitoExtension.class)
class PooledResumeHcTest {
    @Mock ManagedDatabaseStore store;
    @Mock PgbouncerAdminService admin;

    private static ManagedDatabase md(String db) {
        ManagedDatabase m = new ManagedDatabase(db, DatabaseEngineType.POSTGRES, "u_" + db,
                List.of(), Instant.now(), Instant.now(), null);
        m.setPooled(true);
        return m;
    }

    @Test
    void resumesBothInstancesInDeterministicOrder() {
        when(store.findAll()).thenReturn(List.of(md("a")));
        when(admin.isHcEnabled()).thenReturn(true);
        new PooledResumeOnStartup(store, admin).run(null);
        InOrder in = inOrder(admin);
        in.verify(admin).resumeDb("a");
        in.verify(admin).resumeDbHc("a");
    }

    @Test
    void skipsHcWhenDisabled() {
        when(store.findAll()).thenReturn(List.of(md("a")));
        when(admin.isHcEnabled()).thenReturn(false);
        new PooledResumeOnStartup(store, admin).run(null);
        verify(admin).resumeDb("a");
        verify(admin, never()).resumeDbHc("a");
    }
}
