package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.ingest.MongoProfilerParser;
import com.pkmprojects.mongodbserver.audit.ingest.MysqlSlowLogParser;
import com.pkmprojects.mongodbserver.audit.ingest.PostgresJsonlogParser;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Adversarial redaction sweep: every payload below must round-trip through
 * normalization with zero surviving secret-bearing literals.
 */
class RedactionAdversarialTest {

    private static final String[] SECRETS = {
        "hunter2", "s3cr3t-pw", "tok_live_abc123", "AKIAIOSFODNN7EXAMPLE",
        "ghp_deadbeefcafe", "xoxb-token-value", "Bearer eyJhbGciOiJIUzI1NiJ9",
        "-----BEGIN PRIVATE KEY-----", "postgres://u:p%40ss@host/db"
    };

    private static void assertClean(String normalized) {
        for (String s : SECRETS) {
            assertThat(normalized).doesNotContain(s);
        }
        assertThat(normalized).doesNotContain("Authorization", "Cookie", "SCRAM");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM t WHERE pw = 'hunter2'",
        "SELECT * FROM t WHERE created > DATE '2026-01-02' AND token='tok_live_abc123'",
        "SELECT 'it''s hunter2' FROM t",
        "SELECT $$dollar hunter2 quoted$$ FROM t",
        "SELECT * FROM t -- hunter2 comment\nWHERE id = 1",
        "/* hunter2 block */ SELECT 1",
        "SELECT * FROM t WHERE url='https://u:hunter2@h/x'",
        "COPY t FROM STDIN WITH PASSWORD 'hunter2'",
        "SELECT E'multi\\nhunter2\\nline'",
        "SELECT * FROM t WHERE a=1; DROP TABLE u; -- hunter2",
        "SET password = 'hunter2'",
        "GRANT ALL TO role IDENTIFIED BY 'hunter2'"
    })
    void sqlPayloadsNeverSurvive(String sql) {
        assertClean(QueryShapeRedactor.normalize(sql));
    }

    @Test
    void mysqlPasswordStatementsRedacted() {
        String block = "# Time: 2026-09-14T21:10:00Z\n"
                + "# User@Host: root[root] @ localhost []  Id: 1\n"
                + "# Query_time: 0.001  Lock_time: 0.000 Rows_sent: 0  Rows_examined: 0\n"
                + "SET timestamp=1;\n"
                + "CREATE USER 'a'@'%' IDENTIFIED BY 'hunter2'; -- tok_live_abc123";
        var parsed = MysqlSlowLogParser.parseBlock(block, "db");
        assertThat(parsed.event()).isPresent();
        assertClean(parsed.event().get().getNormalizedShape());
    }

    @Test
    void mongoNestedSecretsRedacted() {
        Document cmd = new Document("insert", "coll")
                .append("documents", List.of(
                        new Document("user", "x").append("password", "hunter2")
                                .append("deep", new Document("arr",
                                        List.of(new Document("token", "tok_live_abc123"))))));
        Document doc = new Document("op", "insert").append("ns", "tenant.coll")
                .append("command", cmd).append("ts", new Date())
                .append("client", "9.9.9.9").append("user", "tenant_user@tenant");
        var parsed = MongoProfilerParser.parse(doc);
        assertThat(parsed.event()).isPresent();
        assertClean(parsed.event().get().getNormalizedShape());
        assertThat(parsed.event().get().getNormalizedShape()).contains("password");
    }

    @Test
    void postgresErrorLinesRedacted() {
        String line = "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"u\","
                + "\"dbname\":\"d\",\"error_severity\":\"ERROR\",\"state_code\":\"42601\","
                + "\"message\":\"syntax error\","
                + "\"statement\":\"SELECT * FROM t WHERE pw='hunter2';\"}";
        var parsed = PostgresJsonlogParser.parseLine(line, false, null, null, null);
        assertThat(parsed.event()).isPresent();
        assertClean(parsed.event().get().getNormalizedShape());
        assertThat(parsed.event().get().getSuccess()).isFalse();
    }

    @Test
    void maliciousFieldNamesDoNotBreakShape() {
        Document cmd = new Document("$where", "hunter2")
                .append("__proto__", new Document("x", "tok_live_abc123"));
        Document doc = new Document("op", "command").append("ns", "tenant.coll")
                .append("command", cmd).append("ts", new Date())
                .append("client", "9.9.9.9").append("user", "u@tenant");
        var parsed = MongoProfilerParser.parse(doc);
        assertThat(parsed.event()).isPresent();
        assertClean(parsed.event().get().getNormalizedShape());
    }

    @Test
    void enormousLiteralsBounded() {
        String sql = "SELECT * FROM t WHERE blob = '" + "A".repeat(200000) + "hunter2'";
        String out = QueryShapeRedactor.normalize(sql);
        assertThat(out.length()).isLessThanOrEqualTo(QueryShapeRedactor.MAX_NORMALIZED_LENGTH);
        assertThat(out).doesNotContain("hunter2");
    }
}
