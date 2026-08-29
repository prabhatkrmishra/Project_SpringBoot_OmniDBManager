package com.pkmprojects.mongodbserver.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-threaded lifecycle stress tests against a real MongoDB (auth enabled,
 * standalone). The provisioning lifecycle (provision / reset / delete)
 * is a multi-step check-then-act sequence, so concurrent calls for the same
 * database name must be serialized by the service: exactly one provision can win,
 * and no interleaving may leave orphaned metadata, a user-less database, or a
 * database-less user behind.
 *
 * <p>Each test runs a fixed number of rounds with all worker threads released at
 * the same instant, then asserts a quiescent end state (database present with its
 * user and metadata, or fully absent) and that no unexpected exception type
 * escaped the service.</p>
 *
 * <p>Skipped when Docker is unavailable (same convention as the E2E test).</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "app.mongo.enabled=true",
        "app.admin.username=admin",
        "app.admin.password=admin"
})
class ProvisioningConcurrencyTest {

    private static final String USER = "conc_user";

    @Container
    static GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root")
            .withExposedPorts(27017)
            // the entrypoint starts mongod twice (bootstrap for user creation, then the
            // real server), so wait for the second "waiting for connections"
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2));

    private final List<String> createdDatabases = new java.util.ArrayList<>();
    @Autowired
    private ProvisioningService provisioningService;

    @Autowired
    private MongoDatabaseRepository mongoDatabaseRepository;

    @Autowired
    private ManagedDatabaseRepository managedDatabaseRepository;

    @Autowired
    private MongoClient rootClient;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> "mongodb://root:root@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?authSource=admin");
    }

    @AfterEach
    void cleanup() {
        for (String dbName : createdDatabases) {
            try {
                provisioningService.delete(dbName);
            } catch (RuntimeException ignored) {
                // best-effort cleanup between tests; the next test uses fresh names
            }
        }
        createdDatabases.clear();
    }

    @Test
    void duplicateProvisionAllowsExactlyOneWinner() throws Exception {
        String dbName = "conc_dup";
        createdDatabases.add(dbName);

        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger alreadyExists = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    provisioningService.provision(new CreateDatabaseForm(dbName, USER, "concPass123"));
                    successes.incrementAndGet();
                } catch (DatabaseAlreadyExistsException e) {
                    alreadyExists.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("workers finished in time").isTrue();

        assertThat(unexpected).as("no unexpected exceptions").isEmpty();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(alreadyExists.get()).isEqualTo(workers - 1);

        // exactly one user in <db>.system.users and metadata present
        assertThat(mongoDatabaseRepository.databaseExists(dbName)).isTrue();
        assertThat(managedDatabaseRepository.findByDbName(dbName)).isPresent();
        assertThat(userCount(dbName, USER)).isEqualTo(1);

        // the winner's credentials actually authenticate (the pre-lock race could
        // report success while no user was persisted)
        try (MongoClient winnerClient = MongoClients.create("mongodb://" + USER + ":concPass123@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/" + dbName + "?authSource=" + dbName)) {
            assertThat(winnerClient.getDatabase(dbName).runCommand(new Document("ping", 1)))
                    .extracting(doc -> doc.get("ok"))
                    .isEqualTo(1.0);
        }
    }

    @Test
    void provisionDeleteStormLeavesConsistentState() throws Exception {
        String dbName = "conc_storm";
        createdDatabases.add(dbName);

        int rounds = 100;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        try {
                            provisioningService.provision(new CreateDatabaseForm(dbName, USER, "concPass123"));
                        } catch (DatabaseAlreadyExistsException tolerated) {
                            // a provisioner may lose the race to another provisioner
                        }
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        provisioningService.delete(dbName);
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).as("workers finished in time").isTrue();

        assertThat(unexpected).as("no unexpected exceptions").isEmpty();
        assertConsistent(dbName, USER);
    }

    @Test
    void resetVsDeleteStormIsSerializable() throws Exception {
        String dbName = "conc_reset";
        createdDatabases.add(dbName);

        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        pool.submit(() -> {
            try {
                start.await();
                for (int r = 0; r < rounds; r++) {
                    try {
                        provisioningService.provision(new CreateDatabaseForm(dbName, USER, "concPass123"));
                    } catch (DatabaseAlreadyExistsException tolerated) {
                        // a delete may not have run yet; the round proceeds to reset
                    }
                    for (int i = 0; i < 5; i++) {
                        try {
                            provisioningService.resetPassword(dbName, new ResetPasswordForm(""));
                        } catch (DatabaseNotFoundException tolerated) {
                            // delete won the race before this reset: retry next round
                        }
                    }
                }
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                for (int r = 0; r < rounds * 2; r++) {
                    provisioningService.delete(dbName);
                }
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("workers finished in time").isTrue();

        assertThat(unexpected).as("no unexpected exceptions").isEmpty();
        assertConsistent(dbName, USER);
    }

    private void assertConsistent(String dbName, String userName) {
        boolean dbExists = mongoDatabaseRepository.databaseExists(dbName);
        boolean metaExists = managedDatabaseRepository.findByDbName(dbName).isPresent();
        boolean userExists = userCount(dbName, userName) > 0;
        // a fully provisioned database has its user and metadata; a deleted one has none
        assertThat(dbExists).as("db exists iff metadata exists").isEqualTo(metaExists);
        assertThat(dbExists).as("db exists iff user exists").isEqualTo(userExists);
    }

    /**
     * Counts the users named {@code userName} in {@code dbName} via the
     * {@code usersInfo} command. Direct reads of the {@code system.users}
     * collection (find/countDocuments) return empty results in MongoDB 8 even for
     * the root user, so the authoritative command API is used instead.
     */
    private long userCount(String dbName, String userName) {
        Document result = rootClient.getDatabase(dbName)
                .runCommand(new Document("usersInfo", new Document("user", userName).append("db", dbName)));
        return result.getList("users", Document.class, List.of()).size();
    }
}
