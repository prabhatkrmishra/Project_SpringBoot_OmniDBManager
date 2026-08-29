package com.pkmprojects.mongodbserver.repository;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Data-access gateway for MongoDB server administration using the MongoDB Java driver:
 * database/collection listing, user management, and paginated document reads.
 *
 * <p>Contains no business rules - only driver calls. All operations are bounded
 * (pagination via skip/limit, no unbounded materialization). Only loaded when
 * {@code app.mongo.enabled=true}.</p>
 */
@Repository
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoDatabaseRepository {

    private final MongoClient mongoClient;

    public MongoDatabaseRepository(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    /**
     * @return names of every database in the server, including system databases
     */
    public List<String> listDatabaseNames() {
        List<String> names = new ArrayList<>();
        mongoClient.listDatabaseNames().forEach(names::add);
        return names;
    }

    /**
     * @return map of database name to size on disk in bytes (from {@code sizeOnDisk}
     *         of the {@code listDatabases} command, which reflects actual bytes on
     *         disk rather than pre-allocated file space)
     */
    public Map<String, Long> getDatabaseSizes() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        mongoClient.listDatabases().forEach(doc -> {
            String name = doc.getString("name");
            if (name != null) {
                sizes.put(name, ((Number) doc.get("sizeOnDisk", 0L)).longValue());
            }
        });
        return sizes;
    }

    /**
     * @return the total data size in bytes for {@code dbName}, as reported by
     *         {@code dbStats}. Unlike {@code listDatabases.sizeOnDisk}, this
     *         reflects in-memory data that WiredTiger has not yet flushed to disk.
     */
    public long getDatabaseDataSize(String dbName) {
        Document stats = mongoClient.getDatabase(dbName).runCommand(new Document("dbStats", 1));
        return ((Number) stats.get("dataSize", 0)).longValue();
    }

    /**
     * @return {@code true} when a database with the given name exists on the server
     */
    public boolean databaseExists(String dbName) {
        return listDatabaseNames().contains(dbName);
    }

    /**
     * @return names of the collections in {@code dbName}
     */
    public List<String> listCollectionNames(String dbName) {
        List<String> names = new ArrayList<>();
        mongoClient.getDatabase(dbName).listCollectionNames().forEach(names::add);
        return names;
    }

    /**
     * @return {@code true} when {@code collectionName} exists inside {@code dbName}
     */
    public boolean collectionExists(String dbName, String collectionName) {
        return listCollectionNames(dbName).contains(collectionName);
    }

    /**
     * Creates a Mongo user with readWrite rights scoped to exactly {@code dbName}.
     * The user is created <em>in</em> {@code dbName}, so connection strings of the
     * form {@code mongodb://user:pass@host/dbName} authenticate with the correct
     * authSource by default.
     */
    public void createUser(String dbName, String userName, String password) {
        Document command = new Document("createUser", userName)
                .append("pwd", password)
                .append("roles", List.of(new Document("role", "readWrite").append("db", dbName)));
        mongoClient.getDatabase(dbName).runCommand(command);
    }

    /**
     * Rotates a Mongo user's password, preserving its existing roles.
     */
    public void updateUserPassword(String dbName, String userName, String newPassword) {
        Document command = new Document("updateUser", userName).append("pwd", newPassword);
        mongoClient.getDatabase(dbName).runCommand(command);
    }

    /**
     * Removes the named user from {@code dbName}. Tolerated as a no-op by the
     * caller when the user does not exist (see {@link ProvisioningService}).
     */
    public void dropUser(String dbName, String userName) {
        Document command = new Document("dropUser", userName);
        mongoClient.getDatabase(dbName).runCommand(command);
    }

    /**
     * Materializes a database in MongoDB (which creates databases lazily on first
     * write) by creating a small bootstrap collection.
     */
    public void createDatabase(String dbName) {
        mongoClient.getDatabase(dbName).createCollection("_bootstrap");
    }

    /**
     * Creates a collection inside {@code dbName}. Fails with a driver exception
     * if the collection already exists.
     */
    public void createCollection(String dbName, String collectionName) {
        mongoClient.getDatabase(dbName).createCollection(collectionName);
    }

    /**
     * Drops a collection inside {@code dbName}.
     */
    public void dropCollection(String dbName, String collectionName) {
        mongoClient.getDatabase(dbName).getCollection(collectionName).drop();
    }

    /**
     * Drops the whole database, including any users stored in it.
     */
    public void dropDatabase(String dbName) {
        mongoClient.getDatabase(dbName).drop();
    }

    /**
     * Sends a {@code ping} command to the server. Throws {@link MongoException}
     * when the server is unreachable.
     */
    public void ping() {
        mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
    }

    /**
     * Runs the {@code serverStatus} command. Requires the {@code clusterMonitor}
     * role (or a root/admin account); a db-scoped user will hit an authorization
     * exception, which the caller is expected to handle.
     *
     * @return the raw {@code serverStatus} document
     */
    public Document getServerStatus() {
        return mongoClient.getDatabase("admin").runCommand(new Document("serverStatus", 1));
    }

    /**
     * Runs the {@code dbStats} command for {@code dbName}. Requires the
     * {@code dbStats} action on the database (granted by the built-in
     * {@code read} role and above).
     *
     * @return the raw {@code dbStats} document
     */
    public Document getDbStats(String dbName) {
        return mongoClient.getDatabase(dbName).runCommand(new Document("dbStats", 1));
    }

    /**
     * Runs the {@code collStats} command for {@code dbName}.{@code collectionName}.
     * Requires the {@code collStats} action on the collection (granted by the
     * built-in {@code read} role and above).
     *
     * @return the raw {@code collStats} document
     */
    public Document getCollectionStats(String dbName, String collectionName) {
        return mongoClient.getDatabase(dbName).runCommand(new Document("collStats", collectionName));
    }

    /**
     * @return number of documents in {@code dbName}.{@code collectionName}
     */
    public long countDocuments(String dbName, String collectionName) {
        return mongoClient.getDatabase(dbName).getCollection(collectionName).countDocuments();
    }

    /**
     * Reads one page of documents using skip/limit (bounded materialization).
     *
     * @param dbName         database name
     * @param collectionName collection name
     * @param skip           number of documents to skip
     * @param limit          maximum number of documents to return
     * @return the raw BSON documents
     */
    public List<Document> findDocuments(String dbName, String collectionName, int skip, int limit) {
        List<Document> documents = new ArrayList<>(limit);
        mongoClient.getDatabase(dbName).getCollection(collectionName)
                .find()
                .skip(skip)
                .limit(limit)
                .forEach(documents::add);
        return documents;
    }

    /**
     * Streams every document in {@code dbName}.{@code collectionName} to
     * {@code consumer}, one at a time (never materializes the whole collection).
     */
    public void streamDocuments(String dbName, String collectionName, Consumer<Document> consumer) {
        mongoClient.getDatabase(dbName).getCollection(collectionName).find().forEach(consumer);
    }

    /**
     * @return the index catalog of {@code dbName}.{@code collectionName} as raw
     *         driver documents (including the implicit {@code _id_} index)
     */
    public List<Document> listCollectionIndexes(String dbName, String collectionName) {
        return mongoClient.getDatabase(dbName).getCollection(collectionName)
                .listIndexes()
                .into(new ArrayList<>());
    }

    /**
     * Creates an index on {@code dbName}.{@code collectionName}.
     *
     * @param keys   index key document, e.g. {@code {username: 1}}
     * @param unique whether the index enforces uniqueness
     */
    public void createIndex(String dbName, String collectionName, Document keys, boolean unique) {
        mongoClient.getDatabase(dbName).getCollection(collectionName)
                .createIndex(keys, new IndexOptions().unique(unique));
    }

    /**
     * Inserts many documents into {@code dbName}.{@code collectionName} in one
     * round trip.
     */
    public void insertDocuments(String dbName, String collectionName, List<Document> documents) {
        mongoClient.getDatabase(dbName).getCollection(collectionName).insertMany(documents);
    }

    /**
     * Lists all users defined in {@code dbName} (via the {@code usersInfo} command).
     * System users ({@code __system}, {@code __oplog}) are excluded.
     *
     * @return list of user documents, each containing at least {@code user} and {@code roles}
     */
    public List<Document> getUsers(String dbName) {
        Document result = mongoClient.getDatabase(dbName).runCommand(new Document("usersInfo", 1));
        List<Document> users = result.getList("users", Document.class);
        if (users == null) {
            return List.of();
        }
        return users.stream()
                .filter(doc -> {
                    String name = doc.getString("user");
                    return name != null && !name.startsWith("__");
                })
                .toList();
    }
}