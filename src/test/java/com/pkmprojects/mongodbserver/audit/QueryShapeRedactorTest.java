package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Adversarial redaction tests: no literal-bearing output may survive
 * normalization, and equivalent shapes must hash identically.
 */
class QueryShapeRedactorTest {

    @Test
    void stringAndNumericLiteralsAreRedacted() {
        String out = QueryShapeRedactor.normalize(
                "SELECT * FROM items WHERE name = 'secret-value-123' AND id = 42");
        assertThat(out).doesNotContain("secret-value-123", "42");
        assertThat(out).contains("?");
        assertThat(QueryShapeRedactor.normalize(
                "SELECT * FROM items WHERE name = 'other-value' AND id = 7"))
                .isEqualTo(out);
    }

    @Test
    void passwordsAndTokensNeverSurvive() {
        String out = QueryShapeRedactor.normalize(
                "SELECT * FROM t WHERE password = 's3cr3t!' AND token='tok-abc' -- comment secret");
        assertThat(out).doesNotContain("s3cr3t", "tok-abc", "comment secret");
    }

    @Test
    void urlsWithCredentialsAreScrubbed() {
        String out = QueryShapeRedactor.normalize(
                "SELECT * FROM t WHERE url='https://user:hunter2@example.com/x'");
        assertThat(out).doesNotContain("hunter2", "user:");
        // Unquoted URL contexts keep a ***@ marker as proof of scrubbing.
        String out2 = QueryShapeRedactor.normalize("connect to https://user:hunter2@example.com/x now");
        assertThat(out2).doesNotContain("hunter2");
        assertThat(out2).contains("***@");
    }

    @Test
    void connectionKvSecretsAreScrubbed() {
        String out = QueryShapeRedactor.normalize("host=db password=hunter2 user=u");
        assertThat(out).doesNotContain("hunter2");
    }

    @Test
    void jdbcAndPositionalParamsCollapse() {
        assertThat(QueryShapeRedactor.normalize("SELECT * FROM t WHERE a = ? AND b = $1"))
                .isEqualTo("SELECT * FROM t WHERE a = ? AND b = ?");
    }

    @Test
    void newlinesAndControlCharsAreFlattened() {
        String out = QueryShapeRedactor.normalize("SELECT 1;\nDROP TABLE t;\u0000-- forged\nline2");
        assertThat(out).doesNotContain("\n", "\u0000");
        assertThat(out).doesNotContain("forged");
    }

    @Test
    void oversizedInputIsBounded() {
        String big = "SELECT '" + "x".repeat(100000) + "'";
        String out = QueryShapeRedactor.normalize(big);
        assertThat(out.length()).isLessThanOrEqualTo(QueryShapeRedactor.MAX_NORMALIZED_LENGTH);
        assertThat(out).doesNotContain("x".repeat(100));
    }

    @Test
    void mongoCommandShapeKeepsFieldsRedactsValues() {
        String shape = QueryShapeRedactor.normalizeCommandShape(
                "{\"find\": \"items\", \"filter\": {\"name\": \"secret\", \"n\": 5}}", 4);
        assertThat(shape).doesNotContain("secret");
        assertThat(shape).contains("filter");
    }

    @Test
    void mongoNestedSensitiveFieldsRedactedAtDepth() {
        String shape = QueryShapeRedactor.normalizeCommandShape(
                "{\"update\": \"t\", \"u\": {\"$set\": {\"password\": \"x\", \"deep\": {\"a\": {\"b\": 1}}}}}", 2);
        assertThat(shape).doesNotContain("\"x\"");
    }

    @Test
    void shapeHashIsStableSha256() {
        String a = QueryShapeRedactor.normalize("SELECT * FROM t WHERE id = 1");
        String b = QueryShapeRedactor.normalize("SELECT * FROM t WHERE id = 2");
        assertThat(QueryShapeRedactor.shapeHash(a)).isEqualTo(QueryShapeRedactor.shapeHash(b));
        assertThat(QueryShapeRedactor.shapeHash(a)).hasSize(64);
        assertThat(QueryShapeRedactor.shapeHash("SELECT 1")).isNotEqualTo(QueryShapeRedactor.shapeHash("SELECT 2 FROM t"));
    }

    @Test
    void commandTypeAndOperationClass() {
        assertThat(QueryShapeRedactor.commandType("SELECT * FROM t WHERE id = ?")).isEqualTo("select");
        assertThat(QueryShapeRedactor.operationClass("select")).isEqualTo("read");
        assertThat(QueryShapeRedactor.operationClass("insert")).isEqualTo("write");
        assertThat(QueryShapeRedactor.operationClass("create")).isEqualTo("ddl");
        assertThat(QueryShapeRedactor.operationClass("commit")).isEqualTo("txn");
        assertThat(QueryShapeRedactor.commandType("VACUUM FULL t")).isEqualTo("other");
    }

    @Test
    void blankInputYieldsPlaceholder() {
        assertThat(QueryShapeRedactor.normalize(null)).isEqualTo("?");
        assertThat(QueryShapeRedactor.normalize("   ")).isEqualTo("?");
    }
}
