package com.pkmprojects.mongodbserver;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end flow against real MongoDB (auth enabled, standalone - see
 * {@code MongoDatabaseRepositoryTest} for why not the Testcontainers
 * {@code MongoDBContainer} replica-set image): login, provision a database,
 * connect as the provisioned user, reset the password (old one stops working),
 * and delete everything. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.username=admin",
        "app.admin.password=admin",
        "app.mongo.enabled=true"
})
class MongodbserverApplicationTests {

    @Container
    static GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root")
            .withExposedPorts(27017)
            // the entrypoint starts mongod twice (bootstrap for user creation, then the
            // real server), so wait for the second "waiting for connections"
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MongoDatabaseRepository mongoDatabaseRepository;
    @Autowired
    private ManagedDatabaseRepository managedDatabaseRepository;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> "mongodb://root:root@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?authSource=admin");
    }

    @Test
    void provisionResetDeleteLifecycle() throws Exception {
        // anonymous access is blocked
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        // login as admin (session cookie for the rest of the flow)
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "admin")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);

        // provision a database with explicit credentials
        mockMvc.perform(post("/databases")
                        .session(session)
                        .param("dbName", "testapp")
                        .param("userName", "testapp_user")
                        .param("password", "firstsecret123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/testapp"));

        // the detail page shows the connection string rebuilt from stored metadata
        mockMvc.perform(get("/databases/testapp").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mongodb://testapp_user:firstsecret123@")));

        // the provisioned user can connect and write to its own database
        try (MongoClient appClient = client("testapp_user", "firstsecret123")) {
            appClient.getDatabase("testapp").getCollection("items").insertOne(new Document("name", "widget"));
            assertThat(appClient.getDatabase("testapp").getCollection("items").countDocuments()).isEqualTo(1);
        }

        // reset the password
        mockMvc.perform(post("/databases/testapp/reset")
                        .session(session)
                        .param("password", "secondsecret456")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/testapp"));

        // old password no longer works
        assertThrows(MongoException.class, () -> {
            try (MongoClient oldClient = client("testapp_user", "firstsecret123")) {
                oldClient.getDatabase("testapp").getCollection("items").countDocuments();
            }
        });
        // new password works
        try (MongoClient newClient = client("testapp_user", "secondsecret456")) {
            assertThat(newClient.getDatabase("testapp").getCollection("items").countDocuments()).isEqualTo(1);
        }

        // delete the database: user + metadata gone too
        mockMvc.perform(post("/databases/testapp/delete").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(mongoDatabaseRepository.databaseExists("testapp")).isFalse();
        assertThat(managedDatabaseRepository.findByDbName("testapp")).isEmpty();
        assertThrows(MongoException.class, () -> {
            try (MongoClient goneClient = client("testapp_user", "secondsecret456")) {
                goneClient.getDatabase("testapp").listCollectionNames().first();
            }
        });
    }

    @Test
    void loginRateLimitBlocksBursts() throws Exception {
        // five rapid attempts are allowed (redirect to the login page), the sixth is throttled
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/login")
                            .param("username", "ratelimitprobe")
                            .param("password", "wrong")
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection());
        }
        mockMvc.perform(post("/login")
                        .param("username", "ratelimitprobe")
                        .param("password", "wrong")
                        .with(csrf()))
                .andExpect(status().isTooManyRequests());
    }

    private MongoClient client(String userName, String password) {
        return MongoClients.create("mongodb://" + userName + ":" + password + "@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/testapp");
    }
}
