package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.ingest.MongoProfilerParser;
import com.pkmprojects.mongodbserver.audit.ingest.MysqlSlowLogParser;
import com.pkmprojects.mongodbserver.audit.ingest.PostgresJsonlogParser;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Parser tests over the exact telemetry shapes proven by disposable-container
 * spikes (PG 18 jsonlog, MySQL 8.4 slow log, mongo:8 profiler).
 */
class AuditIngestParserTest {

    @Test
    void postgresStatementLineRedactsLiterals() {
        String line = "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"spikeuser\","
                + "\"dbname\":\"spikedb\",\"pid\":132,\"remote_host\":\"127.0.0.1\","
                + "\"remote_port\":57540,\"session_id\":\"6aa86257.84\",\"ps\":\"idle\","
                + "\"error_severity\":\"LOG\",\"message\":\"statement: SELECT * FROM items WHERE name = 'secret-value-123';\","
                + "\"application_name\":\"psql\",\"backend_type\":\"client backend\",\"query_id\":0}";
        var parsed = PostgresJsonlogParser.parseLine(line, false, "203.0.113.7", null, null);
        assertThat(parsed.event()).isPresent();
        QueryAuditEvent e = parsed.event().get();
        assertThat(e.getNormalizedShape()).doesNotContain("secret-value-123");
        assertThat(e.getProvisionedUser()).isEqualTo("spikeuser");
        assertThat(e.getDatabase()).isEqualTo("spikedb");
        assertThat(e.getSourceIp()).isEqualTo("203.0.113.7");
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.AUTHORITATIVE);
        assertThat(e.getAuditConfidence()).isEqualTo(AuditConfidence.HIGH);
        assertThat(e.getShapeHash()).hasSize(64);
    }

    @Test
    void postgresPooledIsAlwaysInferred() {
        String line = "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"spikeuser\","
                + "\"dbname\":\"spikedb\",\"pid\":132,\"remote_host\":\"127.0.0.1\","
                + "\"session_id\":\"s\",\"error_severity\":\"LOG\","
                + "\"message\":\"statement: SELECT 1;\",\"backend_type\":\"client backend\"}";
        var parsed = PostgresJsonlogParser.parseLine(line, true, "203.0.113.9", "spikeuser", "spikedb");
        assertThat(parsed.event()).isPresent();
        assertThat(parsed.event().get().getAttribution()).isEqualTo(QueryAttribution.INFERRED);
        assertThat(parsed.event().get().getSourceIp()).isEqualTo("203.0.113.9");
        // Backend log address (pooler) must never be presented as the tenant.
        assertThat(parsed.event().get().getSourceIp()).isNotEqualTo("127.0.0.1");
    }

    @Test
    void postgresErrorLineMarksFailure() {
        String line = "{\"timestamp\":\"2026-09-14 21:08:39.530 UTC\",\"user\":\"u\","
                + "\"dbname\":\"d\",\"error_severity\":\"ERROR\",\"state_code\":\"42P01\","
                + "\"message\":\"relation \\\"missing_table\\\" does not exist\","
                + "\"statement\":\"SELECT * FROM missing_table;\"}";
        var parsed = PostgresJsonlogParser.parseLine(line, false, null, null, null);
        assertThat(parsed.event()).isPresent();
        assertThat(parsed.event().get().getSuccess()).isFalse();
        assertThat(parsed.event().get().getErrorCode()).isEqualTo("42P01");
    }

    @Test
    void postgresMalformedLineIsFlagged() {
        assertThat(PostgresJsonlogParser.parseLine("not json", false, null, null, null).malformed()).isTrue();
        assertThat(PostgresJsonlogParser.parseLine(null, false, null, null, null).malformed()).isTrue();
    }

    @Test
    void mysqlSlowLogBlockParsesUserSchemaAndStats() {
        String block = "# Time: 2026-09-14T21:10:00.664691Z\n"
                + "# User@Host: spikeuser[spikeuser] @ localhost []  Id:    13\n"
                + "# Query_time: 0.010115  Lock_time: 0.000006 Rows_sent: 0  Rows_examined: 0\n"
                + "SET timestamp=1789420200;\n"
                + "INSERT INTO items(name) VALUES ('secret-value-123');";
        var parsed = MysqlSlowLogParser.parseBlock(block, "spikedb");
        assertThat(parsed.event()).isPresent();
        QueryAuditEvent e = parsed.event().get();
        assertThat(e.getProvisionedUser()).isEqualTo("spikeuser");
        assertThat(e.getDatabase()).isEqualTo("spikedb");
        assertThat(e.getNormalizedShape()).doesNotContain("secret-value-123");
        assertThat(e.getDurationMs()).isEqualTo(10L);
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.AUTHORITATIVE);
        assertThat(e.getCommandType()).isEqualTo("insert");
        assertThat(e.getOperationClass()).isEqualTo("write");
    }

    @Test
    void mysqlAdminCommandsAreSkipped() {
        var parsed = MysqlSlowLogParser.parseBlock(
                "# User@Host: root[root] @ localhost []  Id: 1\n# administrator command: Quit;", "db");
        assertThat(parsed.event()).isEmpty();
    }

    @Test
    void mongoProfilerDocRedactsCommandValues() {
        Document doc = new Document("op", "update")
                .append("ns", "spikedb.items")
                .append("command", new Document("q", new Document("name", "secret-value-xyz"))
                        .append("u", new Document("$set", new Document("n", 1))))
                .append("millis", 3L)
                .append("ts", new Date(1789420200000L))
                .append("client", "198.51.100.9")
                .append("user", "spikeuser@spikedb")
                .append("nMatched", 1L)
                .append("nModified", 1L);
        var parsed = MongoProfilerParser.parse(doc);
        assertThat(parsed.event()).isPresent();
        QueryAuditEvent e = parsed.event().get();
        assertThat(e.getNormalizedShape()).doesNotContain("secret-value-xyz");
        assertThat(e.getProvisionedUser()).isEqualTo("spikeuser");
        assertThat(e.getSourceIp()).isEqualTo("198.51.100.9");
        assertThat(e.getDatabase()).isEqualTo("spikedb");
        assertThat(e.getAttribution()).isEqualTo(QueryAttribution.AUTHORITATIVE);
        assertThat(e.getDurationMs()).isEqualTo(3L);
        assertThat(e.getRowsAffected()).isEqualTo(1L);
    }

    @Test
    void mongoSystemDatabasesAreExcluded() {
        Document doc = new Document("op", "query").append("ns", "admin.system.users")
                .append("ts", new Date()).append("client", "1.2.3.4").append("user", "root@admin");
        assertThat(MongoProfilerParser.parse(doc).event()).isEmpty();
        assertThat(MongoProfilerParser.parse(null).event()).isEmpty();
    }

    @Test
    void eventObservedAtFallsBackToNow() {
        String line = "{\"error_severity\":\"LOG\",\"message\":\"statement: SELECT 1;\"}";
        var parsed = PostgresJsonlogParser.parseLine(line, false, null, null, null);
        assertThat(parsed.event()).isPresent();
        assertThat(parsed.event().get().getObservedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
