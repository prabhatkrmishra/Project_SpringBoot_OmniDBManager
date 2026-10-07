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
 * <p>Restore and import load the uploaded file fully into memory: the backup
 * format is a single JSON document, callers receive it as a {@link String} and
 * then hand it to {@code Document.parse}, which builds a document tree several
 * times the JSON's own size.
 *
 * <p>So the decompressed cap is derived from the live heap rather than fixed.
 * The previous fixed 1&nbsp;GiB was simply wrong for the deployment this ships
 * into: {@code deploy/deploy.sh} runs {@code -Xmx256m}, where a restore OOMs at
 * roughly 60-80&nbsp;MB decompressed -- about a twelfth of the limit that was
 * supposed to prevent it. And because {@link OutOfMemoryError} is an {@link Error},
 * the {@code catch (IOException)} here could never have contained it regardless.
 *
 * <p>What the cap does guarantee is that an oversized upload is rejected before
 * the heap is exhausted. What it does <em>not</em> guarantee is that the parse
 * itself fits: the document tree is still the dominant allocation. Genuinely
 * large restores need a larger {@code -Xmx}; the divisor below is set so the
 * common case is safe rather than so the worst case is impossible. Streaming the
 * parse from the gzip stream instead of materialising a String would remove the
 * remaining exposure and is the real fix, but it changes three call sites.
 */
public final class BackupLimits {

    /** Max accepted upload size in bytes (compressed for backups, raw for imports). */
    public static final long MAX_UPLOAD_BYTES = 256L * 1024 * 1024;

    /**
     * Heap divisor for the decompressed cap. The decompressed bytes, the String
     * built from them and the parsed document tree are all live at once, so the
     * payload gets an eighth of the heap rather than a share of it.
     */
    private static final long DECOMPRESSED_HEAP_DIVISOR = 8;

    /**
     * Max accepted decompressed backup content in bytes. Derived from the live heap
     * so a small-heap deployment gets a proportionate limit, capped at 1&nbsp;GiB
     * for generously-provisioned hosts.
     */
    public static final long MAX_DECOMPRESSED_BYTES = Math.min(
            1024L * 1024 * 1024,
            Runtime.getRuntime().maxMemory() / DECOMPRESSED_HEAP_DIVISOR);

    private BackupLimits() {
    }

    /**
     * Reads a gzip stream fully, failing with {@link NameNotAllowedException} if
     * the decompressed content exceeds {@code maxBytes} or the stream is not
     * valid gzip. Bounds memory so a highly-compressible upload cannot expand to
     * an unbounded in-memory string -- see the class note for what this does and
     * does not protect against.
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
                            "Backup file is too large to restore (exceeds " + maxBytes
                                    + " bytes decompressed). Raise -Xmx for the manager if this"
                                    + " backup is legitimately that large.");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new NameNotAllowedException("Backup file could not be read or is not a valid backup");
        }
    }
}
