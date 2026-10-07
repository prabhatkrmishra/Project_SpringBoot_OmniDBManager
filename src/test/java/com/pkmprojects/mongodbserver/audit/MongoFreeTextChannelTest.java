package com.pkmprojects.mongodbserver.audit;

import com.pkmprojects.mongodbserver.audit.ingest.MongoProfilerParser;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mongo lets a tenant choose collection names and field names, and both end up in
 * {@code query_audit.normalizedShape}, which the admin UI renders. Without a
 * filter that is a free-text channel: name a collection after your real credential
 * and it is persisted into the control plane's audit trail.
 *
 * <p>These assert the channel is closed, not that the parser is generally sound --
 * the value-redaction side is covered by {@code RedactionAdversarialTest}.
 */
class MongoFreeTextChannelTest {

    private static String shapeFor(String ns, Document cmd) {
        Document doc = new Document("ns", ns).append("op", "command")
                .append("command", cmd)
                .append("ts", new java.util.Date())
                .append("user", "tenant_user")
                .append("appName", "omnidb");
        var parsed = MongoProfilerParser.parse(doc);
        assertThat(parsed.event()).isPresent();
        return parsed.event().get().getNormalizedShape();
    }

    @Test
    void collectionNameCannotSmuggleFreeText() {
        String shape = shapeFor("shopdb.stolen_credential_hunter2_Bearer_abc123",
                new Document("find", "x"));
        assertThat(shape).doesNotContain("Bearer", "hunter2", "abc123", "stolen");
        assertThat(shape).contains(QueryShapeRedactor.identifierToken(
                "stolen_credential_hunter2_Bearer_abc123"));
    }

    @Test
    void fieldNamesCannotSmuggleFreeText() {
        String shape = shapeFor("shopdb.orders",
                new Document("find", "orders").append("filter",
                        new Document("apiKeyAKIAIOSFODNN7EXAMPLE", 1)));
        assertThat(shape).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(shape).contains(QueryShapeRedactor.identifierToken("apiKeyAKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void commandVerbStaysReadable() {
        // opName comes from the server-recorded op, not tenant input, so it is
        // the one identifier worth keeping in the clear.
        assertThat(shapeFor("shopdb.orders", new Document("find", "orders")))
                .contains("find");
    }

    @Test
    void tokensAreStableSoShapesStillGroup() {
        String a = shapeFor("shopdb.orders", new Document("find", "orders").append("filter",
                new Document("status", 1)));
        String b = shapeFor("shopdb.orders", new Document("find", "orders").append("filter",
                new Document("status", "some-other-value")));
        assertThat(a).isEqualTo(b);
        assertThat(QueryShapeRedactor.shapeHash(a)).isEqualTo(QueryShapeRedactor.shapeHash(b));
    }

    @Test
    void differentNamesGetDifferentTokens() {
        String a = shapeFor("shopdb.orders", new Document("find", "orders"));
        String b = shapeFor("shopdb.invoices", new Document("find", "invoices"));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void identifierTokenIsStableAndOpaque() {
        String t = QueryShapeRedactor.identifierToken("orders");
        assertThat(t).isEqualTo(QueryShapeRedactor.identifierToken("orders"));
        assertThat(t).startsWith("n_").hasSize(10);
        assertThat(QueryShapeRedactor.identifierToken("invoices")).isNotEqualTo(t);
        assertThat(QueryShapeRedactor.identifierToken("")).isEqualTo("?");
        assertThat(QueryShapeRedactor.identifierToken(null)).isEqualTo("?");
    }

}
