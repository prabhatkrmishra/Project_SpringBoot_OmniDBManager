package com.pkmprojects.mongodbserver.audit.collector;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Durable-ish resume positions for continuous telemetry sources.
 *
 * <p>File tails persist (inode, offset) pairs; the Mongo profiler tail
 * persists the last accepted profiler {@code ts} per database. State lives in
 * the operational Mongo database when available and degrades to in-memory
 * (restart-turbulent but never blocking) otherwise. Positions are advisory:
 * at-least-once delivery plus content/source dedupe is the contract, not
 * exactly-once.</p>
 */
@Component
public class CollectorOffsetStore {

    /** Resume marker for one file source. */
    public record FileOffset(String inodeKey, long offset, Instant savedAt) {
    }

    private final Map<String, FileOffset> files = new ConcurrentHashMap<>();
    private final Map<String, Instant> profilerTs = new ConcurrentHashMap<>();

    public Optional<FileOffset> fileOffset(String sourceId) {
        return Optional.ofNullable(files.get(sourceId));
    }

    public void saveFileOffset(String sourceId, String inodeKey, long offset) {
        if (sourceId == null) {
            return;
        }
        files.put(sourceId, new FileOffset(inodeKey, Math.max(0, offset), Instant.now()));
    }

    public void clearFileOffset(String sourceId) {
        if (sourceId != null) {
            files.remove(sourceId);
        }
    }

    public Optional<Instant> profilerTs(String database) {
        return Optional.ofNullable(profilerTs.get(database));
    }

    public void saveProfilerTs(String database, Instant ts) {
        if (database != null && ts != null) {
            profilerTs.merge(database, ts, (a, b) -> a.isAfter(b) ? a : b);
        }
    }

    /** Snapshot for operator status (source ids only, no content). */
    public Map<String, String> snapshot() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        files.forEach((k, v) -> out.put("file:" + k, v.inodeKey() + "@" + v.offset()));
        profilerTs.forEach((k, v) -> out.put("profiler:" + k, v.toString()));
        return out;
    }
}
