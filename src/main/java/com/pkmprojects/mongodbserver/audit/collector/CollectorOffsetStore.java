package com.pkmprojects.mongodbserver.audit.collector;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Resume positions for continuous telemetry sources, held in memory only.
 *
 * <p>File tails keep (inode, offset) pairs; the Mongo profiler tail keeps the
 * last accepted profiler {@code ts} per database. Nothing is written to Mongo —
 * this class was documented as persisting to "the operational Mongo database
 * when available", which no code here does.
 *
 * <p>Because the state is per-process, a restart has no offset to resume from
 * and each tailer starts at end-of-file rather than replaying the whole log
 * into the bounded drop-oldest queue. What was written while the manager was
 * down is therefore not audited. Positions are advisory either way:
 * at-least-once delivery plus content/source dedupe is the contract, not
 * exactly-once.
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
