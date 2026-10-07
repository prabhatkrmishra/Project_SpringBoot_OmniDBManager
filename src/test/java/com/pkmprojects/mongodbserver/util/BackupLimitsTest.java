package com.pkmprojects.mongodbserver.util;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the bounded gzip reader that guards backup/restore against
 * decompression amplification blowing up memory.
 */
class BackupLimitsTest {

    private byte[] gzip(String text) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    @Test
    void readBoundedGzipReturnsDecompressedContent() {
        String json = "{\"formatVersion\":1}";

        assertThat(BackupLimits.readBoundedGzip(gzip(json), 1024)).isEqualTo(json);
    }

    @Test
    void readBoundedGzipRejectsContentOverTheLimit() {
        String big = "x".repeat(1000);

        assertThatThrownBy(() -> BackupLimits.readBoundedGzip(gzip(big), 100))
                .isInstanceOf(NameNotAllowedException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void readBoundedGzipRejectsInvalidGzip() {
        assertThatThrownBy(() -> BackupLimits.readBoundedGzip("not gzip".getBytes(StandardCharsets.UTF_8), 1024))
                .isInstanceOf(NameNotAllowedException.class);
    }
    @Test
    void decompressedCapIsDerivedFromTheLiveHeapNotAFixedGiB() {
        // deploy/deploy.sh runs -Xmx256m. A fixed 1 GiB cap meant an OOM at roughly
        // 60-80 MB decompressed -- a twelfth of the limit meant to prevent it.
        long heap = Runtime.getRuntime().maxMemory();
        assertThat(BackupLimits.MAX_DECOMPRESSED_BYTES)
                .as("cap must fit inside this heap")
                .isLessThanOrEqualTo(heap);
        assertThat(BackupLimits.MAX_DECOMPRESSED_BYTES)
                .as("never exceeds the 1 GiB ceiling, even on a large heap")
                .isLessThanOrEqualTo(1024L * 1024 * 1024);
        assertThat(BackupLimits.MAX_DECOMPRESSED_BYTES)
                .as("and is still large enough to be useful")
                .isGreaterThan(0);
    }

    @Test
    void oversizedPayloadIsRejectedWithAnActionableMessage() {
        byte[] big = gzip("x".repeat(4096));
        assertThatThrownBy(() -> BackupLimits.readBoundedGzip(big, 128))
                .isInstanceOf(com.pkmprojects.mongodbserver.error.NameNotAllowedException.class)
                .hasMessageContaining("Raise -Xmx");
    }
}

