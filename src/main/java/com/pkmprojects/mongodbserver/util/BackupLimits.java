package com.pkmprojects.mongodbserver.util;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Shared limits and helpers for backup/restore and import/export uploads.
 *
 * <p>Restore and import load the uploaded file fully into memory (the backup
 * format is a single JSON document parsed with {@code Document.parse}), so a
 * hard cap on both the compressed upload and the decompressed content bounds
 * peak memory and turns an oversized file into a clean error instead of an
 * {@link OutOfMemoryError}. The compressed cap mirrors the fixed 256&nbsp;MB
 * {@code spring.servlet.multipart} limit; the decompressed cap is higher to
 * allow for gzip compression while still bounding memory.
 */
public final class BackupLimits {

    /** Max accepted upload size in bytes (compressed for backups, raw for imports). */
    public static final long MAX_UPLOAD_BYTES = 256L * 1024 * 1024;

    /** Max accepted decompressed backup content in bytes (guards gzip amplification). */
    public static final long MAX_DECOMPRESSED_BYTES = 1024L * 1024 * 1024;

    private BackupLimits() {
    }

    /**
     * Reads a gzip stream fully, failing with {@link NameNotAllowedException} if
     * the decompressed content exceeds {@code maxBytes} or the stream is not
     * valid gzip. Bounds memory so a highly-compressible upload cannot expand to
     * an unbounded in-memory string.
     */
    public static String readBoundedGzip(byte[] content, long maxBytes) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = gzip.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new NameNotAllowedException(
                            "Backup file is too large to restore (exceeds " + maxBytes + " bytes decompressed)");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new NameNotAllowedException("Backup file could not be read or is not a valid backup");
        }
    }
}
