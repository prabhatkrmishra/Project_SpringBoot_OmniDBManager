package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.ingest.MongoProfilerParser;
import com.pkmprojects.mongodbserver.audit.ingest.MysqlSlowLogParser;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Cross-engine IP isolation: same username/database, distinct client IPs.
 * A and B must never inherit each other's IP (no most-recent heuristic —
 * each native telemetry event carries its own connection identity).
 */
class CrossEngineAttributionTest {

    private static String mysqlBlock(String ip, String user) {
        return "# Time: 2026-09-14T21:10:00.664691Z\n"
                + "# User@Host: " + user + "[" + user + "] @ app [" + ip + "]  Id: 13\n"
                + "# Query_time: 0.005  Lock_time: 0.000001 Rows_sent: 1  Rows_examined: 1\n"
                + "SET timestamp=1789420200;\n"
                + "SELECT * FROM orders WHERE id = 1;";
    }

    @Test
    void mysqlSameUserDistinctIpsStaySeparate() {
        var a = MysqlSlowLogParser.parseBlock(mysqlBlock("192.0.2.11", "shop"), "shop");
        var b = MysqlSlowLogParser.parseBlock(mysqlBlock("192.0.2.22", "shop"), "shop");
        assertThat(a.event()).isPresent();
        assertThat(b.event()).isPresent();
        assertThat(a.event().get().getSourceIp()).isEqualTo("192.0.2.11");
        assertThat(b.event().get().getSourceIp()).isEqualTo("192.0.2.22");
        assertThat(a.event().get().getAttribution()).isEqualTo(QueryAttribution.AUTHORITATIVE);
        assertThat(b.event().get().getAttribution()).isEqualTo(QueryAttribution.AUTHORITATIVE);
    }

    @Test
    void mysqlIpv6AndHostname() {
        var v6 = MysqlSlowLogParser.parseBlock(mysqlBlock("2001:db8::9", "shop"), "shop");
        assertThat(v6.event()).isPresent();
        assertThat(v6.event().get().getSourceIp()).isEqualTo("2001:db8::9");
        var host = MysqlSlowLogParser.parseBlock(
                "# Time: 2026-09-14T21:10:00.664691Z\n"
                        + "# User@Host: shop[shop] @ app-host []  Id: 13\n"
                        + "# Query_time: 0.005  Lock_time: 0.0 Rows_sent: 1  Rows_examined: 1\n"
                        + "SET timestamp=1789420200;\nSELECT 1;",
                "shop");
        assertThat(host.event()).isPresent();
        assertThat(host.event().get().getSourceIp()).isNull();
        assertThat(host.event().get().getAttribution()).isEqualTo(QueryAttribution.INFERRED);
    }

    private static Document mongoDoc(String ipPort, String user, String ns) {
        return new Document("op", "query").append("ns", ns)
                .append("command", new Document("find", "orders"))
                .append("millis", 2L).append("ts", new Date())
                .append("client", ipPort).append("user", user);
    }

    @Test
    void mongoSameUserDistinctIpsStaySeparate() {
        var a = MongoProfilerParser.parse(mongoDoc("192.0.2.11:51234", "shop@shop", "shop.orders"));
        var b = MongoProfilerParser.parse(mongoDoc("192.0.2.22:51235", "shop@shop", "shop.orders"));
        assertThat(a.event()).isPresent();
        assertThat(b.event()).isPresent();
        assertThat(a.event().get().getSourceIp()).isEqualTo("192.0.2.11");
        assertThat(a.event().get().getSourcePort()).isEqualTo(51234);
        assertThat(b.event().get().getSourceIp()).isEqualTo("192.0.2.22");
        assertThat(b.event().get().getSourcePort()).isEqualTo(51235);
    }

    @Test
    void mongoIpv6AndHostname() {
        var v6 = MongoProfilerParser.parse(mongoDoc("[2001:db8::9]:27017", "shop@shop", "shop.orders"));
        assertThat(v6.event()).isPresent();
        assertThat(v6.event().get().getSourceIp()).isEqualTo("2001:db8::9");
        var host = MongoProfilerParser.parse(mongoDoc("app-host:51234", "shop@shop", "shop.orders"));
        assertThat(host.event()).isPresent();
        assertThat(host.event().get().getSourceIp()).isNull();
        assertThat(host.event().get().getAttribution()).isEqualTo(QueryAttribution.INFERRED);
    }
}
