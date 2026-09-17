package com.pkmprojects.mongodbserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pkmprojects.mongodbserver.audit.ingest.MongoProfilerParser;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * The collector's own profiler reads (finds on system.profile, typically as
 * the manager root user) must never become tenant audit events — excluded
 * by namespace characteristics, not by username.
 */
class MongoSelfPollExclusionTest {

    @Test
    void profilerReadsOnSystemProfileAreExcluded() {
        Document selfRead = new Document("op", "query").append("ns", "tenantdb.system.profile")
                .append("command", new Document("find", "system.profile"))
                .append("millis", 1L).append("ts", new Date())
                .append("client", "172.18.0.5:51234").append("user", "root@admin");
        assertThat(MongoProfilerParser.parse(selfRead).event()).isEmpty();
    }
}
