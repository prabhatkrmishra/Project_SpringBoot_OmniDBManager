package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness reflects exactly the configured control-plane
 * stores — never tenant databases, never absent engines.
 *
 * <p>Plain hand-rolled fakes (not Mockito mocks): the constructor takes
 * {@code Optional<ConcreteRepo>}, which Mockito generic inference cannot
 * satisfy cleanly.
 */
class ControlPlaneReadinessIndicatorTest {

    private static class FakeRepos {
        int pgPings;
        int myPings;
        int mongoPings;
        RuntimeException pgFail;
        RuntimeException myFail;
        RuntimeException mongoFail;

        volatile boolean mongoHang;

        PostgresDatabaseRepository pg() {
            return new PostgresDatabaseRepository(null, "jdbc:postgresql://127.0.0.1:1/x", "u", "p") {
                @Override public void ping() {
                    pgPings++;
                    if (pgFail != null) throw pgFail;
                }
            };
        }

        MysqlDatabaseRepository my() {
            return new MysqlDatabaseRepository(null, "jdbc:mysql://127.0.0.1:1/x?useSSL=false") {
                @Override public void ping() {
                    myPings++;
                    if (myFail != null) throw myFail;
                }
            };
        }

        MongoDatabaseRepository mongo() {
            return new MongoDatabaseRepository(null) {
                @Override public void ping() {
                    mongoPings++;
                    if (mongoFail != null) throw mongoFail;
                    if (mongoHang) {
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            };
        }
    }

    @Test
    void allConfiguredStoresUp() {
        FakeRepos f = new FakeRepos();
        var ind = new ControlPlaneReadinessIndicator(
                f.pg(), f.my(), f.mongo());
        assertThat(ind.health().getStatus()).isEqualTo(Status.UP);
        assertThat(f.pgPings).isEqualTo(1);
        assertThat(f.myPings).isEqualTo(1);
        assertThat(f.mongoPings).isEqualTo(1);
    }

    @Test
    void oneFailingStoreMarksDownNamingIt() {
        FakeRepos f = new FakeRepos();
        f.myFail = new RuntimeException("connection refused");
        var ind = new ControlPlaneReadinessIndicator(
                f.pg(), f.my(), null);
        var health = ind.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(health.getDetails())).contains("mysql");
    }

    @Test
    void absentEnginesAreIgnoredNotDown() {
        FakeRepos f = new FakeRepos();
        var ind = new ControlPlaneReadinessIndicator(
                null, null, f.mongo());
        assertThat(ind.health().getStatus()).isEqualTo(Status.UP);
        assertThat(f.mongoPings).isEqualTo(1);
    }

    @Test
    void nothingConfiguredIsUp() {
        var ind = new ControlPlaneReadinessIndicator(
                null, null, null);
        assertThat(ind.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void hungStoreTimesOutInsteadOfWedgingProbe() {
        FakeRepos f = new FakeRepos();
        f.mongoHang = true;
        var ind = new ControlPlaneReadinessIndicator(null, null, f.mongo());
        long t0 = System.currentTimeMillis();
        var health = ind.health();
        long wall = System.currentTimeMillis() - t0;
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(health.getDetails())).contains("mongo");
        // Hard cap respected (generous bound for loaded CI): the probe
        // answers in ~1.5s, not after the driver's own 30s+ timeout.
        assertThat(wall).isLessThan(15000);
    }
}
