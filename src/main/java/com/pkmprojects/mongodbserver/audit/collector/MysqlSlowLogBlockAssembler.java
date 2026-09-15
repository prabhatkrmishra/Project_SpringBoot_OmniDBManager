package com.pkmprojects.mongodbserver.audit.collector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Assembles MySQL slow-log lines into per-statement blocks.
 *
 * <p>A block starts at a {@code # Time:} line and runs through the statement
 * text up to (not including) the next {@code # Time:} line. Multi-line
 * statements, {@code use db;} selectors, {@code SET timestamp=} lines, and
 * {@code # administrator command:} markers all stay inside the block; the
 * parser decides what is a statement. Bounded: a single block is capped at
 * {@value #MAX_BLOCK_LINES} lines / {@value #MAX_BLOCK_CHARS} chars; overflow
 * content is dropped with the block still delivered once (truncated).</p>
 */
public class MysqlSlowLogBlockAssembler {

    static final int MAX_BLOCK_LINES = 2000;
    static final int MAX_BLOCK_CHARS = 256 * 1024;

    private final StringBuilder current = new StringBuilder();
    private int currentLines;
    private boolean truncated;

    /**
     * Feeds one slow-log line; completed blocks go to {@code blocks}.
     * Call {@link #flush} at end-of-poll to deliver a trailing partial block
     * only when it already holds a complete statement (contains a
     * {@code # Query_time:} line); otherwise it stays buffered for the next
     * poll (partial-block handling across polls).
     */
    public void feed(String line, Consumer<String> blocks) {
        if (line == null) {
            return;
        }
        if (line.startsWith("# Time:") && current.length() > 0) {
            emit(blocks);
        }
        append(line);
    }

    /** Delivers the buffered block if it looks complete; else keeps it. */
    public void flush(Consumer<String> blocks) {
        if (current.length() == 0) {
            return;
        }
        String block = current.toString();
        if (block.contains("# Query_time:")) {
            emit(blocks);
        }
        // else: partial block (statement text not yet arrived) — keep buffering.
    }

    /** Forces delivery of whatever is buffered (rotation/restart path). */
    public void flushForced(Consumer<String> blocks) {
        if (current.length() > 0) {
            emit(blocks);
        }
    }

    public boolean hasPartial() {
        return current.length() > 0;
    }

    public void reset() {
        current.setLength(0);
        currentLines = 0;
        truncated = false;
    }

    private void append(String line) {
        if (currentLines >= MAX_BLOCK_LINES || current.length() >= MAX_BLOCK_CHARS) {
            truncated = true;
            return;
        }
        if (current.length() > 0) {
            current.append('\n');
        }
        current.append(line);
        currentLines++;
    }

    private void emit(Consumer<String> blocks) {
        String block = current.toString();
        reset();
        if (block.isBlank()) {
            return;
        }
        if (truncated) {
            block = block + "\n-- [block truncated at collector bound]";
        }
        blocks.accept(block);
    }

    /** Splits raw text into blocks (convenience for tests). */
    public static List<String> assemble(String text) {
        List<String> out = new ArrayList<>();
        MysqlSlowLogBlockAssembler a = new MysqlSlowLogBlockAssembler();
        for (String line : text.split("\n")) {
            a.feed(line, out::add);
        }
        a.flushForced(out::add);
        return out;
    }
}
