package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.dto.MonitorSnapshot;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.util.Json;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Produces live MongoDB server snapshots for the monitor page. Rates (ops/sec,
 * network bytes/sec) are deltas between consecutive snapshots, so they are zero
 * on the first snapshot and reset to zero if the server restarts (counters go
 * backwards).
 *
 * <p>Like the health dashboard, everything derived from {@code serverStatus}
 * degrades to {@code null} when the connected user lacks the required
 * privileges, while reachability and database sizes keep working.</p>
 */
@Service
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final Clock clock;
    private final AtomicReference<CounterState> previousCounters = new AtomicReference<>();

    public MonitorService(MongoDatabaseRepository mongoDatabaseRepository, Clock clock) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.clock = clock;
    }

    public MonitorSnapshot getSnapshot() {
        Instant now = clock.instant();
        if (!ping()) {
            previousCounters.set(null);
            return new MonitorSnapshot(false, now, null, null, null, 0, null, null, null, null, null);
        }

        Document status = readServerStatus();

        String version = null;
        Long uptimeSeconds = null;
        Integer connectionCount = null;
        MonitorSnapshot.OpsRate ops = null;
        MonitorSnapshot.NetworkRate network = null;
        MonitorSnapshot.MemUsage mem = null;
        MonitorSnapshot.LockQueue lock = null;

        if (status != null) {
            version = status.getString("version");
            uptimeSeconds = toLong(status.get("uptime"));
            Document connections = status.get("connections", Document.class);
            if (connections != null) {
                connectionCount = toInteger(connections.get("current"));
            }
            mem = readMem(status);
            lock = readLock(status);

            Document opcounters = status.get("opcounters", Document.class);
            Document networkDoc = status.get("network", Document.class);
            if (opcounters != null && networkDoc != null) {
                RatePair rates = updateCounters(opcounters, networkDoc, now);
                ops = rates.ops;
                network = rates.network;
            }
        }

        int databaseCount = 0;
        Long totalStorageBytes = null;
        try {
            Map<String, Long> sizes = mongoDatabaseRepository.getDatabaseSizes();
            databaseCount = sizes.size();
            totalStorageBytes = sizes.values().stream().mapToLong(Long::longValue).sum();
        } catch (MongoException e) {
            log.warn("Could not read database sizes for monitor page", e);
        }

        return new MonitorSnapshot(true, now, version, uptimeSeconds, connectionCount,
                databaseCount, totalStorageBytes, ops, network, mem, lock);
    }

    /**
     * Serializes a snapshot as a JSON object (the app deliberately has no
     * Jackson, so JSON is assembled by hand via {@link Json}).
     */
    public String serialize(MonitorSnapshot snapshot) {
        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"reachable\":").append(snapshot.reachable())
                .append(",\"measuredAt\":").append(Json.jsonString(snapshot.measuredAt().toString()))
                .append(",\"version\":").append(Json.jsonString(snapshot.version()))
                .append(",\"uptimeSeconds\":").append(number(snapshot.uptimeSeconds()))
                .append(",\"connectionCount\":").append(number(snapshot.connectionCount()))
                .append(",\"databaseCount\":").append(snapshot.databaseCount())
                .append(",\"totalStorageBytes\":").append(number(snapshot.totalStorageBytes()))
                .append(",\"ops\":").append(snapshot.ops() == null ? "null" : opsJson(snapshot.ops()))
                .append(",\"network\":").append(snapshot.network() == null ? "null" : networkJson(snapshot.network()))
                .append(",\"mem\":").append(snapshot.mem() == null ? "null" : memJson(snapshot.mem()))
                .append(",\"lock\":").append(snapshot.lock() == null ? "null" : lockJson(snapshot.lock()))
                .append('}');
        return json.toString();
    }

    private boolean ping() {
        try {
            mongoDatabaseRepository.ping();
            return true;
        } catch (MongoException e) {
            log.warn("MongoDB ping failed", e);
            return false;
        }
    }

    private Document readServerStatus() {
        try {
            return mongoDatabaseRepository.getServerStatus();
        } catch (MongoException e) {
            log.warn("serverStatus unavailable for monitor (insufficient privileges?)", e);
            return null;
        }
    }

    private MonitorSnapshot.MemUsage readMem(Document status) {
        Document mem = status.get("mem", Document.class);
        if (mem == null) {
            return null;
        }
        return new MonitorSnapshot.MemUsage(toLong(mem.get("resident")), toLong(mem.get("virtual")));
    }

    private MonitorSnapshot.LockQueue readLock(Document status) {
        Document globalLock = status.get("globalLock", Document.class);
        if (globalLock == null) {
            return null;
        }
        Document queue = globalLock.get("currentQueue", Document.class);
        Document active = globalLock.get("activeClients", Document.class);
        return new MonitorSnapshot.LockQueue(
                queue == null ? 0 : toLong(queue.get("total"), 0),
                queue == null ? 0 : toLong(queue.get("readers"), 0),
                queue == null ? 0 : toLong(queue.get("writers"), 0),
                active == null ? 0 : toLong(active.get("total"), 0),
                active == null ? 0 : toLong(active.get("readers"), 0),
                active == null ? 0 : toLong(active.get("writers"), 0));
    }

    /**
     * Advances the counter baseline and computes per-second rates since the
     * previous snapshot. Counters going backwards (server restart) yield zero
     * rates and re-baseline.
     */
    private RatePair updateCounters(Document opcounters, Document networkDoc, Instant now) {
        long insert = toLong(opcounters.get("insert"), 0);
        long query = toLong(opcounters.get("query"), 0);
        long update = toLong(opcounters.get("update"), 0);
        long delete = toLong(opcounters.get("delete"), 0);
        long command = toLong(opcounters.get("command"), 0);
        long bytesIn = toLong(networkDoc.get("bytesIn"), 0);
        long bytesOut = toLong(networkDoc.get("bytesOut"), 0);

        CounterState current = new CounterState(insert, query, update, delete, command, bytesIn, bytesOut, now);
        CounterState previous = previousCounters.getAndSet(current);
        if (previous == null) {
            return new RatePair(null, null);
        }
        long elapsedSeconds = Math.max(1, Duration.between(previous.measuredAt(), now).toSeconds());
        MonitorSnapshot.OpsRate ops = new MonitorSnapshot.OpsRate(
                rate(insert, previous.insert(), elapsedSeconds),
                rate(query, previous.query(), elapsedSeconds),
                rate(update, previous.update(), elapsedSeconds),
                rate(delete, previous.delete(), elapsedSeconds),
                rate(command, previous.command(), elapsedSeconds));
        MonitorSnapshot.NetworkRate network = new MonitorSnapshot.NetworkRate(
                rate(bytesIn, previous.bytesIn(), elapsedSeconds),
                rate(bytesOut, previous.bytesOut(), elapsedSeconds));
        return new RatePair(ops, network);
    }

    private static long rate(long current, long previous, long elapsedSeconds) {
        return current >= previous ? Math.round((current - previous) / (double) elapsedSeconds) : 0;
    }

    private static String opsJson(MonitorSnapshot.OpsRate ops) {
        return "{\"insert\":" + ops.insert() + ",\"query\":" + ops.query()
                + ",\"update\":" + ops.update() + ",\"delete\":" + ops.delete()
                + ",\"command\":" + ops.command() + "}";
    }

    private static String networkJson(MonitorSnapshot.NetworkRate network) {
        return "{\"bytesInPerSecond\":" + network.bytesInPerSecond()
                + ",\"bytesOutPerSecond\":" + network.bytesOutPerSecond() + "}";
    }

    private static String memJson(MonitorSnapshot.MemUsage mem) {
        return "{\"residentMb\":" + number(mem.residentMb()) + ",\"virtualMb\":" + number(mem.virtualMb()) + "}";
    }

    private static String lockJson(MonitorSnapshot.LockQueue lock) {
        return "{\"queueTotal\":" + lock.queueTotal() + ",\"queueReaders\":" + lock.queueReaders()
                + ",\"queueWriters\":" + lock.queueWriters() + ",\"activeClientsTotal\":" + lock.activeClientsTotal()
                + ",\"activeClientsReaders\":" + lock.activeClientsReaders()
                + ",\"activeClientsWriters\":" + lock.activeClientsWriters() + "}";
    }

    private static String number(Number value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static long toLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private record CounterState(long insert, long query, long update, long delete, long command,
                                long bytesIn, long bytesOut, Instant measuredAt) {
    }

    private record RatePair(MonitorSnapshot.OpsRate ops, MonitorSnapshot.NetworkRate network) {
    }
}
