package com.pkmprojects.mongodbserver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The console client replaces pgjdbc for pooler-console traffic: pgjdbc's
 * connect-time {@code SET} probe dies on the console db ("SET failed",
 * proven live), so this client sends no setup queries at all.
 */
class PgbouncerConsoleClientTest {

    // ── deterministic SCRAM math (vectors generated from RFC 5802 §5 inputs) ──

    @Test
    void scramProofMatchesKnownVector() throws Exception {
        var state = PgbouncerConsoleClient.ScramState.begin(
                "pencil",
                "n=user,r=fyko+d2lbbFgONRv9qkxdaw",
                "r=fyko+d2lbbFgONRv9qkxdaw3rfcNHYJY1ZvWVs7j,s=QSXCR+Q6sek8bf92,i=4096");
        assertThat(state.combinedNonce).isEqualTo("fyko+d2lbbFgONRv9qkxdaw3rfcNHYJY1ZvWVs7j");
        assertThat(state.clientProofB64()).isEqualTo("MG5vWZvihAWHrP8gKwnxWp3pE5vWh+0EOdrNOkxHLTw=");
        assertThat(state.clientProofB64()).isNotEqualTo("bogus");
    }

    @Test
    void scramServerVerificationAcceptsGoodSignature() throws Exception {
        var state = PgbouncerConsoleClient.ScramState.begin(
                "pencil",
                "n=user,r=fyko+d2lbbFgONRv9qkxdaw",
                "r=fyko+d2lbbFgONRv9qkxdaw3rfcNHYJY1ZvWVs7j,s=QSXCR+Q6sek8bf92,i=4096");
        state.verifyServer("v=mtm/TFqg0CgTENcFuD98HldHXRy7pXDJHcue9FI+II8=");
    }

    @Test
    void scramServerVerificationRejectsForgedSignature() throws Exception {
        var state = PgbouncerConsoleClient.ScramState.begin(
                "pencil",
                "n=user,r=fyko+d2lbbFgONRv9qkxdaw",
                "r=fyko+d2lbbFgONRv9qkxdaw3rfcNHYJY1ZvWVs7j,s=QSXCR+Q6sek8bf92,i=4096");
        assertThatThrownBy(() -> state.verifyServer("v=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("signature mismatch");
    }

    @Test
    void scramRejectsMalformedServerFirst() {
        assertThatThrownBy(() -> PgbouncerConsoleClient.ScramState.begin("p", "n=u,r=n", "garbage"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void saslEscapeHandlesEqualsAndComma() {
        assertThat(PgbouncerConsoleClient.saslEscape("a=b,c")).isEqualTo("a=3Db=2Cc");
        assertThat(PgbouncerConsoleClient.saslEscape("plain")).isEqualTo("plain");
    }

    // ── wire round-trips against a scripted fake console ──

    /** Minimal fake: SCRAM auth for one user, then canned SHOW/command replies. No TLS. */
    static class FakeConsole implements AutoCloseable {
        final ServerSocket server;
        final String user;
        final String password;
        final AtomicReference<String> firstQuery = new AtomicReference<>();
        volatile boolean sawStartupParamLeak;

        FakeConsole(String user, String password) throws Exception {
            this.server = new ServerSocket(0);
            this.user = user;
            this.password = password;
            Thread t = new Thread(this::serve);
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        @Override public void close() throws Exception { server.close(); }

        private void serve() {
            try (Socket s = server.accept();
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
                // StartupMessage
                int len = in.readInt();
                int proto = in.readInt();
                byte[] rest = new byte[len - 8];
                in.readFully(rest);
                String startup = new String(rest, StandardCharsets.UTF_8);
                if (startup.contains("extra_float_digits") || startup.contains("application_name")) {
                    sawStartupParamLeak = true;
                }
                // Offer SCRAM
                byte[] mechs = "SCRAM-SHA-256\0\0".getBytes(StandardCharsets.US_ASCII);
                out.writeByte('R'); out.writeInt(4 + 4 + mechs.length); out.writeInt(10); out.write(mechs); out.flush();
                // SASLInitialResponse
                expect(in, 'p');
                int mlen = in.readInt();
                byte[] mbody = new byte[mlen - 4];
                in.readFully(mbody);
                // mechanism NUL + int32 n + client-first
                int z = indexOfZero(mbody, 0);
                int n = ((mbody[z + 1] & 0xFF) << 24) | ((mbody[z + 2] & 0xFF) << 16)
                        | ((mbody[z + 3] & 0xFF) << 8) | (mbody[z + 4] & 0xFF);
                String clientFirst = new String(mbody, z + 5, n, StandardCharsets.UTF_8);
                String cnonce = after(clientFirst, ",r=");
                String serverFirst = "r=" + cnonce + "SERVER,s=" + Base64.getEncoder().encodeToString("saltsalt".getBytes()) + ",i=4096";
                sendAuthData(out, 11, serverFirst);
                // SASLResponse
                expect(in, 'p');
                int rlen = in.readInt();
                byte[] rbody = new byte[rlen - 4];
                in.readFully(rbody);
                // SASLResponse has no inner length — the rest IS the message.
                String clientFinal = new String(rbody, 0, rbody.length, StandardCharsets.UTF_8);
                // verify proof like a server would
                String proof = after(clientFinal, ",p=");
                String bare = clientFirst.substring("n,,".length());
                String noProof = clientFinal.substring(0, clientFinal.indexOf(",p="));
                String authMsg = bare + "," + serverFirst + "," + noProof;
                byte[] salted = pbkdf2(password);
                byte[] stored = MessageDigest.getInstance("SHA-256")
                        .digest(hmac(salted, "Client Key".getBytes(StandardCharsets.US_ASCII)));
                byte[] sig = hmac(stored, authMsg.getBytes(StandardCharsets.UTF_8));
                byte[] ck = hmac(salted, "Client Key".getBytes(StandardCharsets.US_ASCII));
                byte[] expected = new byte[ck.length];
                for (int i = 0; i < expected.length; i++) expected[i] = (byte) (ck[i] ^ sig[i]);
                if (!MessageDigest.isEqual(expected, Base64.getDecoder().decode(proof))) {
                    sendError(out, "password authentication failed");
                    return;
                }
                byte[] serverKey = hmac(salted, "Server Key".getBytes(StandardCharsets.US_ASCII));
                sendAuthData(out, 12, "v=" + Base64.getEncoder()
                        .encodeToString(hmac(serverKey, authMsg.getBytes(StandardCharsets.UTF_8))));
                out.writeByte('R'); out.writeInt(8); out.writeInt(0); out.flush();
                out.writeByte('Z'); out.writeInt(5); out.writeByte('I'); out.flush();
                // queries until close
                while (true) {
                    int type;
                    try { type = in.readUnsignedByte(); } catch (Exception e) { return; }
                    int qlen = in.readInt();
                    byte[] qbody = new byte[qlen - 4];
                    in.readFully(qbody);
                    if (type != 'Q') { sendError(out, "only simple query supported"); return; }
                    String sql = new String(qbody, 0, qbody.length - 1, StandardCharsets.UTF_8);
                    firstQuery.compareAndSet(null, sql);
                    if (sql.startsWith("SHOW POOLS")) {
                        List<String[]> poolRows = new ArrayList<>();
                        poolRows.add(new String[] { "mydb", user, "3", "transaction" });
                        sendRows(out,
                                new String[] { "database", "user", "cl_active", "pool_mode" },
                                poolRows,
                                "SHOW");
                    } else if (sql.startsWith("PAUSE")) {
                        sendRows(out, new String[0], List.<String[]>of(), "PAUSE");
                    } else {
                        sendError(out, "unknown command");
                    }
                }
            } catch (Exception ignored) {
                // test server — client disconnects end the script
            }
        }

        private static void expect(DataInputStream in, int type) throws Exception {
            int t = in.readUnsignedByte();
            if (t != type) throw new IllegalStateException("expected " + (char) type + " got " + (char) t);
        }

        private static void sendAuthData(DataOutputStream out, int authType, String data) throws Exception {
            byte[] b = data.getBytes(StandardCharsets.UTF_8);
            out.writeByte('R'); out.writeInt(4 + 4 + b.length); out.writeInt(authType); out.write(b); out.flush();
        }

        private static void sendRows(DataOutputStream out, String[] cols, List<String[]> rows, String tag) throws Exception {
            java.io.ByteArrayOutputStream desc = new java.io.ByteArrayOutputStream();
            desc.write((cols.length >> 8) & 0xFF); desc.write(cols.length & 0xFF);
            for (String c : cols) {
                byte[] b = c.getBytes(StandardCharsets.UTF_8);
                desc.write(b, 0, b.length); desc.write(0);
                desc.write(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 25 }, 0, 18);
            }
            byte[] d = desc.toByteArray();
            out.writeByte('T'); out.writeInt(4 + d.length); out.write(d);
            for (String[] row : rows) {
                java.io.ByteArrayOutputStream rb = new java.io.ByteArrayOutputStream();
                rb.write((row.length >> 8) & 0xFF); rb.write(row.length & 0xFF);
                for (String v : row) {
                    byte[] b = v.getBytes(StandardCharsets.UTF_8);
                    rb.write((b.length >> 24) & 0xFF); rb.write((b.length >> 16) & 0xFF);
                    rb.write((b.length >> 8) & 0xFF); rb.write(b.length & 0xFF);
                    rb.write(b, 0, b.length);
                }
                byte[] r = rb.toByteArray();
                out.writeByte('D'); out.writeInt(4 + r.length); out.write(r);
            }
            byte[] t = (tag + "\0").getBytes(StandardCharsets.UTF_8);
            out.writeByte('C'); out.writeInt(4 + t.length); out.write(t);
            out.writeByte('Z'); out.writeInt(5); out.writeByte('I');
            out.flush();
        }

        private static void sendError(DataOutputStream out, String msg) throws Exception {
            byte[] m = msg.getBytes(StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream eb = new java.io.ByteArrayOutputStream();
            eb.write('M'); eb.write(m, 0, m.length); eb.write(0);
            byte[] e = eb.toByteArray();
            out.writeByte('E'); out.writeInt(4 + e.length); out.write(e);
            out.writeByte('Z'); out.writeInt(5); out.writeByte('I');
            out.flush();
        }

        private static int indexOfZero(byte[] b, int from) {
            for (int i = from; i < b.length; i++) if (b[i] == 0) return i;
            return b.length;
        }

        private static String after(String s, String marker) {
            int i = s.indexOf(marker);
            return i < 0 ? "" : s.substring(i + marker.length());
        }

        private static byte[] hmac(byte[] key, byte[] data) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        }

        private static byte[] pbkdf2(String password) throws Exception {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(), "saltsalt".getBytes(StandardCharsets.UTF_8), 4096, 256)).getEncoded();
        }
    }

    @Test
    void pauseRoundTripAndNoSetupQueryLeak() throws Exception {
        try (FakeConsole fake = new FakeConsole("admin", "s3cret");
                PgbouncerConsoleClient c = PgbouncerConsoleClient.connect(
                        "127.0.0.1", fake.port(), "admin", "s3cret", false, 2000, 5000)) {
            var r = c.query("PAUSE \"mydb\"");
            assertThat(r.tag()).isEqualTo("PAUSE");
            assertThat(r.rows()).isEmpty();
            assertThat(fake.firstQuery.get()).isEqualTo("PAUSE \"mydb\"");
            assertThat(fake.sawStartupParamLeak).isFalse();
        }
    }

    @Test
    void showPoolsRowsParse() throws Exception {
        try (FakeConsole fake = new FakeConsole("stats", "s3cret");
                PgbouncerConsoleClient c = PgbouncerConsoleClient.connect(
                        "127.0.0.1", fake.port(), "stats", "s3cret", false, 2000, 5000)) {
            var r = c.query("SHOW POOLS");
            assertThat(r.rows()).hasSize(1);
            assertThat(r.rows().get(0)[0]).isEqualTo("mydb");
            assertThat(r.tag()).isEqualTo("SHOW");
        }
    }

    @Test
    void wrongPasswordSurfacesConsoleError() throws Exception {
        try (FakeConsole fake = new FakeConsole("admin", "right-pass")) {
            assertThatThrownBy(() -> PgbouncerConsoleClient.connect(
                    "127.0.0.1", fake.port(), "admin", "wrong-pass", false, 2000, 5000))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("password authentication failed");
        }
    }
}
