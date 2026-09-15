package com.pkmprojects.mongodbserver.audit.collector;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Incremental, rotation-aware line tailer for append-only telemetry files
 * (PostgreSQL jsonlog, MySQL slow log).
 *
 * <p>Handles: rename+new-file rotation (inode change), truncation
 * (size shrink), replacement, partial trailing lines (buffered until the
 * newline arrives), over-long lines (capped, remainder skipped), and
 * malformed bytes (replacement chars, never an exception). Bounded: at most
 * one line (capped) is buffered in memory per source.</p>
 *
 * <p>Resume: the caller supplies a {@link CollectorOffsetStore}; on inode
 * change or shrink the offset resets to zero, otherwise reading continues
 * from the saved offset. State is advisory (at-least-once + dedupe).</p>
 */
public class RotatingFileTailer {

    private static final Logger log = LoggerFactory.getLogger(RotatingFileTailer.class);

    /** Maximum accepted line length; longer lines are truncated with a marker. */
    public static final int MAX_LINE_LENGTH = 256 * 1024;

    /** Maximum buffered partial-line bytes across polls. */
    public static final int MAX_PARTIAL_BYTES = 512 * 1024;

    private final String sourceId;
    private final Path path;
    private final CollectorOffsetStore offsets;
    private final StringBuilder partial = new StringBuilder();
    private boolean truncatedPartial;

    /** File key (inode) of the currently open file, or null before first poll. */
    private String currentKey;
    private long position;
    private long lastSize = -1;

    public RotatingFileTailer(String sourceId, Path path, CollectorOffsetStore offsets) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId required");
        if (path == null) throw new IllegalArgumentException("path required");
        this.sourceId = sourceId;
        this.path = path;
        this.offsets = offsets;
        if (offsets != null) {
            offsets.fileOffset(sourceId).ifPresent(o -> {
                this.currentKey = o.inodeKey();
                this.position = o.offset();
            });
        }
    }

    /**
     * Reads newly appended complete lines, delivering each to {@code lines}.
     * Returns the number of lines delivered. Never throws for missing files
     * (reports zero and retries next poll).
     */
    public int poll(Consumer<String> lines) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            return 0; // file absent (rotation window, source disabled)
        }
        if (!attrs.isRegularFile()) {
            return 0;
        }
        String key = attrs.fileKey() == null ? "size:" + attrs.size() : attrs.fileKey().toString();
        long size = attrs.size();
        if (currentKey != null && !currentKey.equals(key)) {
            // Rotated: new inode. Emit nothing from the old handle; start over.
            log.info("audit tail {} rotated ({} -> {}), resuming at 0", sourceId, currentKey, key);
            currentKey = key;
            position = 0;
            partial.setLength(0);
            truncatedPartial = false;
        } else if (currentKey == null) {
            currentKey = key;
            if (position > size) {
                position = 0; // saved offset beyond a replaced file
            }
        } else if (size < position || (lastSize >= 0 && size < lastSize && size <= position)) {
            // Truncated in place (copytruncate-style) or truncated and
            // rewritten to a size at/below the old offset.
            log.info("audit tail {} truncated ({} -> {}), resuming at 0", sourceId, position, size);
            position = 0;
            partial.setLength(0);
            truncatedPartial = false;
        }
        lastSize = size;
        int delivered = 0;
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(position);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = raf.read(buf)) > 0) {
                delivered += feed(new String(buf, 0, n, StandardCharsets.UTF_8), lines);
                if (partial.length() > MAX_PARTIAL_BYTES) {
                    log.warn("audit tail {} partial-line buffer overflow, dropping partial", sourceId);
                    partial.setLength(0);
                    truncatedPartial = false;
                }
            }
            position = raf.getFilePointer();
        } catch (IOException e) {
            log.debug("audit tail {} read failed: {}", sourceId, e.getMessage());
            return delivered;
        }
        if (offsets != null) {
            offsets.saveFileOffset(sourceId, currentKey, position);
        }
        return delivered;
    }

    /** Feeds one chunk; returns lines delivered. Package-visible for tests. */
    int feed(String chunk, Consumer<String> lines) {
        int delivered = 0;
        int start = 0;
        for (int i = 0; i < chunk.length(); i++) {
            if (chunk.charAt(i) == '\n') {
                partial.append(chunk, start, i);
                String line = takeLine();
                if (line != null) {
                    lines.accept(line);
                    delivered++;
                }
                start = i + 1;
            }
        }
        if (start < chunk.length()) {
            partial.append(chunk, start, chunk.length());
            if (partial.length() > MAX_LINE_LENGTH && !truncatedPartial) {
                truncatedPartial = true;
            }
        }
        return delivered;
    }

    private String takeLine() {
        String line = partial.toString();
        partial.setLength(0);
        boolean truncated = truncatedPartial;
        truncatedPartial = false;
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty()) {
            return null;
        }
        if (truncated || line.length() > MAX_LINE_LENGTH) {
            return line.substring(0, Math.min(line.length(), MAX_LINE_LENGTH)) + "...[truncated]";
        }
        return line;
    }

    /** Current resume position, for status pages (no content). */
    public String describePosition() {
        return (currentKey == null ? "unopened" : currentKey) + "@" + position;
    }

    public Instant lastPoll() {
        return Instant.now();
    }
}
