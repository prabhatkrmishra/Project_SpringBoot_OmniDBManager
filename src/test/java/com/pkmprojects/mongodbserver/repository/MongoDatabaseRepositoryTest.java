package com.pkmprojects.mongodbserver.repository;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Driver-gateway tests against a real MongoDB with authentication enabled, so
 * user creation, password rotation and dropping are tested with real semantics.
 * Skipped automatically when Docker is unavailable.
 *
 * <p>Runs as a standalone mongod (not a replica set) - the Testcontainers
 * {@code MongoDBContainer} in 2.x initializes a single-node replica set by
 * default but its {@code rs.initiate()} runs unauthenticated, which fails once
 * {@code MONGO_INITDB_ROOT_USERNAME/PASSWORD} exist. A plain container with the
 * official image entrypoint gives us the same authenticated standalone server
 * the app runs against.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoDatabaseRepositoryTest {

    @Container
    static GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root")
            .withExposedPorts(27017)
            // the entrypoint starts mongod twice (bootstrap for user creation, then the
            // real server), so wait for the second "waiting for connections"
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2));

    private static MongoClient rootClient;
    private static MongoDatabaseRepository repository;

    @BeforeAll
    static void startClient() {
        rootClient = MongoClients.create("mongodb://root:root@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?authSource=admin");
        repository = new MongoDatabaseRepository(rootClient);
    }

    @AfterAll
    static void closeClient() {
        if (rootClient != null) {
            rootClient.close();
        }
    }

    @BeforeEach
    void cleanUp() {
        repository.listDatabaseNames().stream()
                .filter(name -> !name.equals("admin") && !name.equals("local") && !name.equals("config"))
                .forEach(repository::dropDatabase);
        // users survive a database drop; drop the test user explicitly, tolerating absence
        try {
            repository.dropUser("testapp", "testapp_user");
        } catch (MongoException ignored) {
            // user not created yet
        }
    }

    @Test
    void createDatabaseAndCollections() {
        assertThat(repository.databaseExists("testapp")).isFalse();

        repository.createDatabase("testapp");
        repository.createCollection("testapp", "items");
        repository.createCollection("testapp", "orders");

        assertThat(repository.databaseExists("testapp")).isTrue();
        assertThat(repository.listCollectionNames("testapp")).containsExactlyInAnyOrder("_bootstrap", "items", "orders");
        assertThat(repository.collectionExists("testapp", "items")).isTrue();
        assertThat(repository.collectionExists("testapp", "nope")).isFalse();
    }

    @Test
    void provisionedUserCanWriteToOwnDatabase() {
        repository.createDatabase("testapp");
        repository.createUser("testapp", "testapp_user", "firstsecret123");

        try (MongoClient appClient = MongoClients.create("mongodb://testapp_user:firstsecret123@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/testapp")) {
            appClient.getDatabase("testapp").getCollection("items").insertOne(new Document("name", "widget"));
            assertThat(appClient.getDatabase("testapp").getCollection("items").countDocuments()).isEqualTo(1);
        }
    }

    @Test
    void updateUserPasswordInvalidatesOldCredential() {
        repository.createDatabase("testapp");
        repository.createUser("testapp", "testapp_user", "firstsecret123");

        repository.updateUserPassword("testapp", "testapp_user", "secondsecret456");

        try (MongoClient oldClient = MongoClients.create("mongodb://testapp_user:firstsecret123@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/testapp")) {
            assertThrows(MongoException.class,
                    () -> oldClient.getDatabase("testapp").getCollection("items").countDocuments());
        }
        try (MongoClient newClient = MongoClients.create("mongodb://testapp_user:secondsecret456@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/testapp")) {
            assertThat(newClient.getDatabase("testapp").getCollection("items").countDocuments()).isZero();
        }
    }

    @Test
    void updateUserPasswordRecreatesUserWhenDropped() {
        repository.createDatabase("testapp");
        repository.createUser("testapp", "testapp_user", "firstsecret123");
        repository.dropUser("testapp", "testapp_user");

        // Self-healing: a dropped user is recreated rather than failing on updateUser.
        repository.updateUserPassword("testapp", "testapp_user", "secondsecret456");

        try (MongoClient newClient = MongoClients.create("mongodb://testapp_user:secondsecret456@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/testapp")) {
            assertThat(newClient.getDatabase("testapp").getCollection("items").countDocuments()).isZero();
        }
    }

    @Test
    void dropDatabaseAndUser() {
        repository.createDatabase("testapp");
        repository.createUser("testapp", "testapp_user", "firstsecret123");

        repository.dropUser("testapp", "testapp_user");
        repository.dropDatabase("testapp");

        assertThat(repository.databaseExists("testapp")).isFalse();
        try (MongoClient goneClient = MongoClients.create("mongodb://testapp_user:firstsecret123@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/testapp")) {
            assertThrows(MongoException.class,
                    () -> goneClient.getDatabase("testapp").listCollectionNames().first());
        }
    }

    @Test
    void countAndFindDocumentsAreBounded() {
        repository.createDatabase("testapp");
        repository.createCollection("testapp", "items");
        for (int i = 0; i < 120; i++) {
            rootClient.getDatabase("testapp").getCollection("items").insertOne(new Document("_id", i));
        }

        assertThat(repository.countDocuments("testapp", "items")).isEqualTo(120);
        assertThat(repository.findDocuments("testapp", "items", 0, 50)).hasSize(50);
        assertThat(repository.findDocuments("testapp", "items", 100, 50)).hasSize(20);
    }

    @Test
    void getDatabaseSizesReturnsNonNegativeForEmptyDatabase() {
        repository.createDatabase("testapp");

        Map<String, Long> sizes = repository.getDatabaseSizes();

        assertThat(sizes).containsKey("testapp");
        assertThat(sizes.get("testapp")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void getDatabaseSizesIncreasesAfterInsertingDocuments() {
        repository.createDatabase("testapp");
        repository.createCollection("testapp", "items");
        long sizeBefore = repository.getDatabaseSizes().getOrDefault("testapp", 0L);

        for (int i = 0; i < 1000; i++) {
            rootClient.getDatabase("testapp").getCollection("items")
                    .insertOne(new Document("name", "item-" + i).append("value", "x".repeat(200)));
        }
        // sizeOnDisk reflects WiredTiger checkpoints, not the live data size, so
        // force a flush before measuring the post-insert on-disk size.
        rootClient.getDatabase("admin").runCommand(new Document("fsync", 1));
        long sizeAfter = repository.getDatabaseSizes().getOrDefault("testapp", 0L);

        assertThat(sizeAfter).isGreaterThan(sizeBefore);
    }

    @Test
    void getDatabaseSizesIncludesAllCreatedDatabases() {
        repository.createDatabase("alpha");
        repository.createDatabase("beta");

        Map<String, Long> sizes = repository.getDatabaseSizes();

        assertThat(sizes).containsKey("alpha");
        assertThat(sizes).containsKey("beta");
    }

    @Test
    void getUsersReturnsCreatedUser() {
        repository.createDatabase("testapp");
        repository.createUser("testapp", "testapp_user", "secret123");

        var users = repository.getUsers("testapp");

        assertThat(users).hasSize(1);
        assertThat(users.get(0).getString("user")).isEqualTo("testapp_user");
    }

    @Test
    void getUsersExcludesSystemUsers() {
        repository.createDatabase("testapp");
        repository.createUser("testapp", "testapp_user", "secret123");

        var users = repository.getUsers("testapp");

        assertThat(users).noneMatch(doc -> doc.getString("user").startsWith("__"));
    }

    @Test
    void pingSucceedsAgainstRunningServer() {
        repository.ping();
    }

    @Test
    void getServerStatusReturnsVersion() {
        Document status = repository.getServerStatus();

        assertThat(status.getString("version")).isNotBlank();
        assertThat(status.get("uptime")).isInstanceOf(Number.class);
    }

    @Test
    void getDbStatsReturnsAggregateNumbers() {
        repository.createDatabase("testapp");
        repository.createCollection("testapp", "items");
        rootClient.getDatabase("testapp").getCollection("items").insertOne(new Document("name", "widget"));

        Document stats = repository.getDbStats("testapp");

        assertThat(stats.getString("db")).isEqualTo("testapp");
        assertThat(((Number) stats.get("collections")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) stats.get("objects")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("dataSize")).isInstanceOf(Number.class);
        assertThat(stats.get("storageSize")).isInstanceOf(Number.class);
    }

    @Test
    void getCollectionStatsReturnsPerCollectionNumbers() {
        repository.createDatabase("testapp");
        repository.createCollection("testapp", "items");
        rootClient.getDatabase("testapp").getCollection("items").insertOne(new Document("name", "widget"));

        Document stats = repository.getCollectionStats("testapp", "items");

        assertThat(stats.getString("ns")).isEqualTo("testapp.items");
        assertThat(((Number) stats.get("count")).longValue()).isEqualTo(1);
        assertThat(stats.get("size")).isInstanceOf(Number.class);
        assertThat(stats.get("storageSize")).isInstanceOf(Number.class);
        assertThat(((Number) stats.get("nindexes")).intValue()).isGreaterThanOrEqualTo(1);
    }
}
