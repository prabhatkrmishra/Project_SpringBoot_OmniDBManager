package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.collector.BridgeSessionLogTailer;
import com.pkmprojects.mongodbserver.audit.collector.CollectorOffsetStore;
import com.pkmprojects.mongodbserver.audit.collector.MysqlSlowLogBlockAssembler;
import com.pkmprojects.mongodbserver.audit.collector.RotatingFileTailer;
import com.pkmprojects.mongodbserver.audit.ingest.BridgeSessionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Operational tail tests: rotation, truncation, partial lines, multi-line
 * MySQL blocks, bridge envelope parsing, offset resume. No Docker needed.
 */
class CollectorTailsTest {

    @TempDir
    Path tmp;

    @Test
    void postgresTailReadsAppendsAndSurvivesRotation() throws Exception {
        Path f = tmp.resolve("pg.json");
        Files.writeString(f, "{\"a\":1}\n");
        CollectorOffsetStore offsets = new CollectorOffsetStore();
        RotatingFileTailer tailer = new RotatingFileTailer("pg", f, offsets);
        List<String> out = new ArrayList<>();

        // No saved offset for this source, so the tailer starts at end-of-file and
        // the pre-existing line is deliberately not replayed.
        assertThat(tailer.poll(out::add)).isZero();
        assertThat(out).isEmpty();

        // Append + partial line buffering.
        Files.write(f, "{\"b\":2}\n{\"partial\":".getBytes(), StandardOpenOption.APPEND);
        out.clear();
        assertThat(tailer.poll(out::add)).isEqualTo(1);
        assertThat(out).containsExactly("{\"b\":2}");
        // Partial still buffered; complete it.
        Files.write(f, "\"x\"}\n".getBytes(), StandardOpenOption.APPEND);
        out.clear();
        assertThat(tailer.poll(out::add)).isEqualTo(1);
        assertThat(out.get(0)).contains("partial");

        // Rotation: replace file with new inode.
        Path rotated = tmp.resolve("pg.json.1");
        Files.move(f, rotated);
        Files.writeString(f, "{\"c\":3}\n");
        out.clear();
        assertThat(tailer.poll(out::add)).isEqualTo(1);
        assertThat(out).containsExactly("{\"c\":3}");

        // Truncation resets to zero (copytruncate: the tailer observes the
        // empty window, then the rewritten content).
        try (var raf = new java.io.RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(0);
        }
        out.clear();
        assertThat(tailer.poll(out::add)).isEqualTo(0);
        Files.writeString(f, "{\"d\":4}\n");
        out.clear();
        assertThat(tailer.poll(out::add)).isEqualTo(1);
        assertThat(out).containsExactly("{\"d\":4}");
    }

    @Test
    void overlongLinesAreCappedNotExplosive() throws Exception {
        Path f = tmp.resolve("big.log");
        Files.writeString(f, "");
        RotatingFileTailer tailer = new RotatingFileTailer("big", f, new CollectorOffsetStore());
        List<String> out = new ArrayList<>();
        // Establish the end-of-file start before writing, otherwise the first
        // poll would skip the line by design.
        assertThat(tailer.poll(out::add)).isZero();
        Files.writeString(f, "x".repeat(300 * 1024) + "\n");
        assertThat(tailer.poll(out::add)).isEqualTo(1);
        assertThat(out.get(0).length()).isLessThanOrEqualTo(RotatingFileTailer.MAX_LINE_LENGTH + 20);
    }

    @Test
    void mysqlAssemblerGroupsMultilineBlocks() {
        String log = "# Time: 2026-09-14T21:10:00Z\n"
                + "# User@Host: u[u] @ localhost []  Id: 1\n"
                + "# Query_time: 0.001  Lock_time: 0.000 Rows_sent: 1  Rows_examined: 1\n"
                + "SET timestamp=1;\n"
                + "SELECT *\nFROM t\nWHERE id = 1;\n"
                + "# Time: 2026-09-14T21:10:01Z\n"
                + "# User@Host: u[u] @ localhost []  Id: 1\n"
                + "# Query_time: 0.002  Lock_time: 0.000 Rows_sent: 0  Rows_examined: 0\n"
                + "SET timestamp=2;\n"
                + "INSERT INTO t VALUES (1);";
        List<String> blocks = MysqlSlowLogBlockAssembler.assemble(log);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).contains("SELECT *\nFROM t");
        assertThat(blocks.get(1)).contains("INSERT INTO t");
    }

    @Test
    void mysqlAssemblerHoldsPartialAcrossPolls() {
        MysqlSlowLogBlockAssembler a = new MysqlSlowLogBlockAssembler();
        List<String> out = new ArrayList<>();
        a.feed("# Time: 2026-09-14T21:10:00Z", out::add);
        a.feed("# User@Host: u[u] @ localhost []  Id: 1", out::add);
        a.flush(out::add);
        assertThat(out).isEmpty(); // no Query_time yet — stays buffered
        assertThat(a.hasPartial()).isTrue();
        a.feed("# Query_time: 0.001  Lock_time: 0.000 Rows_sent: 0  Rows_examined: 0", out::add);
        a.feed("SET timestamp=1;", out::add);
        a.feed("SELECT 1;", out::add);
        a.flush(out::add);
        assertThat(out).hasSize(1);
    }

    @Test
    void bridgeEnvelopeAndRawLinesParse() {
        String raw = "{\"audit\":\"bridge-session-start\",\"session_id\":\"s1\",\"at\":\"2026-09-14T21:00:00Z\","
                + "\"client_ip\":\"203.0.113.7\",\"client_port\":51234,\"user\":\"shop_user\","
                + "\"database\":\"shop\",\"mode\":\"direct\",\"profile\":\"none\",\"route\":\"direct\"}";
        var p = BridgeSessionLogTailer.parseLine(raw);
        assertThat(p.session()).isPresent();
        BridgeSessionEvent s = p.session().get();
        assertThat(s.clientIp()).isEqualTo("203.0.113.7");
        assertThat(s.username()).isEqualTo("shop_user");
        assertThat(s.pooled()).isFalse();

        // Docker json-file envelope with escaped payload.
        String envelope = "{\"log\":\"{\\\"audit\\\":\\\"bridge-session-start\\\","
                + "\\\"session_id\\\":\\\"s2\\\",\\\"at\\\":\\\"2026-09-14T21:00:00Z\\\","
                + "\\\"client_ip\\\":\\\"10.0.0.9\\\",\\\"client_port\\\":40001,"
                + "\\\"user\\\":\\\"u\\\",\\\"database\\\":\\\"d\\\","
                + "\\\"mode\\\":\\\"pooled\\\",\\\"profile\\\":\\\"standard\\\",\\\"route\\\":\\\"pooled\\\"}\","
                + "\"stream\":\"stderr\",\"time\":\"2026-09-14T21:00:00Z\"}";
        var p2 = BridgeSessionLogTailer.parseLine(envelope);
        assertThat(p2.session()).isPresent();
        assertThat(p2.session().get().pooled()).isTrue();
        assertThat(p2.session().get().clientIp()).isEqualTo("10.0.0.9");

        // End events route to session cleanup, never carry identity.
        var end = BridgeSessionLogTailer.parseLine(
                "{\"audit\":\"bridge-session-end\",\"session_id\":\"s1\",\"at\":\"2026-09-14T21:05:00Z\",\"duration_ms\":300}");
        assertThat(end.isEnd()).isTrue();
        assertThat(end.sessionId()).isEqualTo("s1");
        assertThat(end.session()).isEmpty();

        assertThat(BridgeSessionLogTailer.parseLine("unrelated log line").session()).isEmpty();
        assertThat(BridgeSessionLogTailer.parseLine(null).session()).isEmpty();
    }

    @Test
    void bridgeSessionRecordRejectsSecretsByConstruction() {
        // The record only has identity fields — there is nowhere to put SQL.
        BridgeSessionEvent s = new BridgeSessionEvent(java.time.Instant.now(), "s", "1.2.3.4", 123,
                "u", "d", "direct", "none", "direct", null, java.time.Instant.now(), null);
        assertThat(s.toString()).doesNotContain("password", "SCRAM", "SELECT");
    }

    @Test
    void freshTailerDoesNotReplayExistingHistory() throws Exception {
        // The behaviour this whole change exists for. A restart leaves the offset
        // store empty, and replaying the entire log would flood the bounded
        // drop-oldest queue and re-persist history the audit trail already has.
        Path f = tmp.resolve("replay.json");
        Files.writeString(f, "{\"old\":1}\n{\"older\":2}\n");
        RotatingFileTailer tailer = new RotatingFileTailer("replay", f, new CollectorOffsetStore());
        List<String> out = new ArrayList<>();

        assertThat(tailer.poll(out::add)).isZero();
        assertThat(out).isEmpty();

        // New lines after that point are still delivered.
        Files.writeString(f, "{\"new\":3}\n", StandardOpenOption.APPEND);
        assertThat(tailer.poll(out::add)).isEqualTo(1);
        assertThat(out).containsExactly("{\"new\":3}");
    }

    @Test
    void savedOffsetOfZeroIsHonouredRatherThanTreatedAsUnknown() throws Exception {
        // Offset 0 is a real position, not an absence of one. If it were
        // confused with "no offset known", a genuine resume-from-start would be
        // skipped and those lines lost.
        Path f = tmp.resolve("zero.json");
        Files.writeString(f, "{\"first\":1}\n");
        CollectorOffsetStore offsets = new CollectorOffsetStore();

        // Poll once so the store holds this file's real inode key. Using a made-up
        // key here would take the rotation branch and pass for the wrong reason.
        RotatingFileTailer initial = new RotatingFileTailer("zero", f, offsets);
        initial.poll(new ArrayList<String>()::add);
        String realKey = offsets.fileOffset("zero").orElseThrow().inodeKey();
        assertThat(offsets.fileOffset("zero").orElseThrow().offset()).isPositive();

        // Now a genuine "resume from the very beginning".
        offsets.saveFileOffset("zero", realKey, 0);
        RotatingFileTailer resumed = new RotatingFileTailer("zero", f, offsets);
        List<String> out = new ArrayList<>();
        assertThat(resumed.poll(out::add)).isEqualTo(1);
        assertThat(out).containsExactly("{\"first\":1}");
    }

    @Test
    void offsetsResumeAndSnapshot() {
        CollectorOffsetStore store = new CollectorOffsetStore();
        assertThat(store.fileOffset("pg")).isEmpty();
        store.saveFileOffset("pg", "inode-1", 1234);
        assertThat(store.fileOffset("pg").get().offset()).isEqualTo(1234);
        AtomicReference<String> snap = new AtomicReference<>("");
        snap.set(store.snapshot().toString());
        assertThat(snap.get()).contains("pg");
        store.saveProfilerTs("tenant", java.time.Instant.parse("2026-09-14T21:00:00Z"));
        assertThat(store.profilerTs("tenant")).isPresent();
    }
}
