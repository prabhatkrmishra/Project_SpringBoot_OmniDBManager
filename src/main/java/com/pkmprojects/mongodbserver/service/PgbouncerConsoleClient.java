package com.pkmprojects.mongodbserver.service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal PostgreSQL-wire client for the PgBouncer admin/stats console
 * ({@code db=pgbouncer}). Speaks just enough protocol: optional TLS upgrade
 * via SSLRequest, SCRAM-SHA-256 auth, and the simple query protocol.
 *
 * <p>Why not JDBC here: the pgjdbc driver sends a connect-time
 * {@code SET extra_float_digits} probe whenever the reported server version
 * is below 12, and the pooler advertises its own {@code 1.24.x/bouncer}
 * version on the console — so every JDBC console connection dies with
 * "SET failed" (proven live; psql works because libpq never sends that
 * probe). This client sends no setup queries at all: StartupMessage carries
 * only user+database, exactly what the console accepts. Tenant databases
 * accept SET, so only the console paths use this client.
 *
 * <p>TLS semantics intentionally mirror {@code sslmode=require}: the server
 * certificate is NOT verified (loopback connections where the hostname can
 * never match the public SAN). The pooler still terminates real TLS —
 * plaintext is refused by {@code client_tls_sslmode=require} when the proxy
 * is on, and by explicit {@code requireTls=false} only where the pooler is
 * known-plaintext (proxy off, same-host loopback).
 */
public final class PgbouncerConsoleClient implements AutoCloseable {

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int PROTOCOL_VERSION = 196608;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Rows + command tag of one simple-query round trip. */
    public record Result(List<String[]> rows, String tag) {
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final int socketTimeoutMs;

    private PgbouncerConsoleClient(Socket socket, int socketTimeoutMs) throws IOException {
        this.socket = socket;
        this.socketTimeoutMs = socketTimeoutMs;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /**
     * Opens an authenticated console session. Throws on any failure —
     * callers decide whether console ops are best-effort (lifecycle) or
     * fatal (monitoring).
     */
    public static PgbouncerConsoleClient connect(String host, int port, String user, String password,
            boolean requireTls, int connectTimeoutMs, int socketTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(socketTimeoutMs);
        } catch (IOException e) {
            closeQuiet(socket);
            throw e;
        }
        if (requireTls) {
            try {
                socket = upgradeTls(socket, host);
                socket.setSoTimeout(socketTimeoutMs);
            } catch (IOException e) {
                closeQuiet(socket);
                throw e;
            }
        }
        PgbouncerConsoleClient client;
        try {
            client = new PgbouncerConsoleClient(socket, socketTimeoutMs);
            client.startup(user, password);
        } catch (IOException | RuntimeException e) {
            closeQuiet(socket);
            throw e;
        }
        return client;
    }

    private static void closeQuiet(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {
            // best-effort close of a half-opened socket
        }
    }

    /** Runs one simple-query statement (PAUSE/RESUME/RECONNECT/SHOW ...). */
    public Result query(String sql) throws IOException {
        byte[] body = (sql + "\0").getBytes(StandardCharsets.UTF_8);
        out.writeByte('Q');
        out.writeInt(body.length + 4);
        out.write(body);
        out.flush();
        List<String[]> rows = new ArrayList<>();
        String tag = "";
        while (true) {
            int type;
            try {
                type = in.readUnsignedByte();
            } catch (EOFException e) {
                throw new IOException("Console closed the connection mid-query", e);
            }
            int len = in.readInt();
            byte[] payload = new byte[len - 4];
            in.readFully(payload);
            switch (type) {
                case 'T' -> { /* row description — text format assumed */ }
                case 'D' -> rows.add(parseDataRow(payload));
                case 'C' -> tag = nulString(payload, 0);
                case 'Z' -> { return new Result(rows, tag); }
                case 'E' -> throw new IOException("Console error: " + serverMessage(payload));
                case 'N' -> { /* notice — ignore */ }
                default -> throw new IOException("Unexpected console message: " + (char) type);
            }
        }
    }

    @Override
    public void close() {
        try {
            out.writeByte('X');
            out.writeInt(4);
            out.flush();
        } catch (IOException ignored) {
            // terminate is best-effort; the socket close below is the real cleanup
        }
        try { socket.close(); } catch (IOException ignored) {
            // see above
        }
    }

    // ── handshake ────────────────────────────────────────────────────

    /**
     * Returns the layered TLS socket (owns and auto-closes the raw socket).
     * Callers must use THIS socket's streams afterwards, never the raw ones.
     */
    private static Socket upgradeTls(Socket socket, String host) throws IOException {
        DataOutputStream raw = new DataOutputStream(socket.getOutputStream());
        raw.writeInt(8);
        raw.writeInt(SSL_REQUEST_CODE);
        raw.flush();
        int reply = socket.getInputStream().read();
        if (reply != 'S') {
            throw new IOException("Pooler refused TLS upgrade (reply=" + reply + ", want 'S')");
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { trustAll() }, new SecureRandom());
            var ssl = (javax.net.ssl.SSLSocket) ctx.getSocketFactory().createSocket(
                    socket, host, socket.getPort(), true);
            ssl.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
            ssl.startHandshake();
            return ssl;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS upgrade failed: " + e.getMessage(), e);
        }
    }

    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                // require-semantics: encryption without identity check (loopback)
            }
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                // require-semantics: encryption without identity check (loopback)
            }
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };
    }

    private void startup(String user, String password) throws IOException {
        byte[] userB = user.getBytes(StandardCharsets.UTF_8);
        byte[] dbB = "pgbouncer".getBytes(StandardCharsets.UTF_8);
        int len = 8 + ("user".length() + 1 + userB.length + 1) + ("database".length() + 1 + dbB.length + 1) + 1;
        out.writeInt(len);
        out.writeInt(PROTOCOL_VERSION);
        out.writeBytes("user");
        out.writeByte(0);
        out.write(userB);
        out.writeByte(0);
        out.writeBytes("database");
        out.writeByte(0);
        out.write(dbB);
        out.writeByte(0);
        out.writeByte(0);
        out.flush();
        boolean authenticated = false;
        while (!authenticated) {
            int type;
            try {
                type = in.readUnsignedByte();
            } catch (EOFException e) {
                throw new IOException("Console closed the connection during startup", e);
            }
            int msgLen = in.readInt();
            byte[] payload = new byte[msgLen - 4];
            in.readFully(payload);
            switch (type) {
                case 'R' -> {
                    int authType = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                            | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                    if (authType == 0) {
                        authenticated = true;
                    } else if (authType == 10) {
                        scramAuth(user, password, payload);
                    } else if (authType == 5) {
                        throw new IOException("Pooler asked for MD5 auth — unsupported (SCRAM-only deployment)");
                    } else {
                        throw new IOException("Unsupported console auth type: " + authType);
                    }
                }
                case 'E' -> throw new IOException("Console startup error: " + serverMessage(payload));
                case 'N' -> { /* notice — ignore */ }
                case 'S', 'K' -> { /* ParameterStatus / BackendKeyData — ignore */ }
                case 'Z' -> authenticated = true; // some servers go ready without explicit AuthOk
                default -> throw new IOException("Unexpected console startup message: " + (char) type);
            }
        }
        // Drain to ReadyForQuery so the first query starts clean.
        // (If the server already sent it, this loop exits on the first Z.)
        drainToReady();
    }

    private void drainToReady() throws IOException {
        socket.setSoTimeout(500);
        try {
            while (true) {
                int type;
                try {
                    type = in.readUnsignedByte();
                } catch (java.net.SocketTimeoutException e) {
                    return; // nothing more pending — proceed
                } catch (EOFException e) {
                    throw new IOException("Console closed the connection during startup", e);
                }
                int msgLen = in.readInt();
                byte[] payload = new byte[msgLen - 4];
                in.readFully(payload);
                if (type == 'Z') return;
                if (type == 'E') throw new IOException("Console startup error: " + serverMessage(payload));
            }
        } finally {
            try { socket.setSoTimeout(socketTimeoutMs); } catch (IOException ignored) {
                // restoring the timeout is best-effort; the next read sets its own
            }
        }
    }

    // ── SCRAM-SHA-256 (RFC 5802, no channel binding) ─────────────────

    private void scramAuth(String user, String password, byte[] saslList) throws IOException {
        String mechanisms = new String(saslList, 4, saslList.length - 5, StandardCharsets.US_ASCII);
        boolean offered = false;
        for (String m : mechanisms.split("\0")) {
            if ("SCRAM-SHA-256".equals(m)) { offered = true; break; }
        }
        if (!offered) {
            throw new IOException("Pooler did not offer SCRAM-SHA-256 (offered: " + mechanisms.replace("\0", ",") + ")");
        }
        String cnonce = randomNonce(18);
        String clientFirstBare = "n=" + saslEscape(user) + ",r=" + cnonce;
        byte[] initial = ("n,," + clientFirstBare).getBytes(StandardCharsets.UTF_8);
        byte[] mech = "SCRAM-SHA-256\0".getBytes(StandardCharsets.US_ASCII);
        out.writeByte('p');
        out.writeInt(4 + mech.length + 4 + initial.length);
        out.write(mech);
        out.writeInt(initial.length);
        out.write(initial);
        out.flush();

        String serverFirst = readSaslContinue();
        ScramState state = ScramState.begin(password, clientFirstBare, serverFirst);
        byte[] finalMsg = ("c=biws,r=" + state.combinedNonce + ",p=" + state.clientProofB64()).getBytes(StandardCharsets.UTF_8);
        out.writeByte('p');
        out.writeInt(4 + 4 + finalMsg.length);
        out.writeInt(finalMsg.length);
        out.write(finalMsg);
        out.flush();

        String serverFinal = readSaslContinue();
        state.verifyServer(serverFinal);
        // AuthenticationOk (type 0) + ParameterStatus parade follow; the
        // startup loop consumes them.
    }

    private String readSaslContinue() throws IOException {
        while (true) {
            int type;
            try {
                type = in.readUnsignedByte();
            } catch (EOFException e) {
                throw new IOException("Console closed the connection during SCRAM", e);
            }
            int msgLen = in.readInt();
            byte[] payload = new byte[msgLen - 4];
            in.readFully(payload);
            if (type == 'R') {
                int authType = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                        | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                if (authType == 11 || authType == 12) {
                    return new String(payload, 4, payload.length - 4, StandardCharsets.UTF_8);
                }
                if (authType == 0) {
                    throw new IOException("Pooler finished auth before SCRAM completed");
                }
            }
            if (type == 'E') throw new IOException("Console auth error: " + serverMessage(payload));
            if (type != 'N') throw new IOException("Unexpected console auth message: " + (char) type);
        }
    }

    /** Pure SCRAM math — package-visible for deterministic unit tests. */
    static final class ScramState {
        final String combinedNonce;
        private final byte[] saltedPassword;
        private final String authMessage;

        private ScramState(String combinedNonce, byte[] saltedPassword, String authMessage) {
            this.combinedNonce = combinedNonce;
            this.saltedPassword = saltedPassword;
            this.authMessage = authMessage;
        }

        static ScramState begin(String password, String clientFirstBare, String serverFirst) throws IOException {
            String nonce = null, saltB64 = null, iterS = null;
            for (String part : serverFirst.split(",")) {
                if (part.startsWith("r=")) nonce = part.substring(2);
                else if (part.startsWith("s=")) saltB64 = part.substring(2);
                else if (part.startsWith("i=")) iterS = part.substring(2);
            }
            if (nonce == null || saltB64 == null || iterS == null) {
                throw new IOException("Malformed SCRAM server-first message");
            }
            try {
                byte[] salt = Base64.getDecoder().decode(saltB64);
                int iter = Integer.parseInt(iterS);
                byte[] salted = pbkdf2(password.getBytes(StandardCharsets.UTF_8), salt, iter);
                String clientFinalNoProof = "c=biws,r=" + nonce;
                String authMessage = clientFirstBare + "," + serverFirst + "," + clientFinalNoProof;
                return new ScramState(nonce, salted, authMessage);
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed SCRAM server-first message: " + e.getMessage(), e);
            }
        }

        String clientProofB64() throws IOException {
            try {
                byte[] clientKey = hmac(saltedPassword, "Client Key".getBytes(StandardCharsets.US_ASCII));
                byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
                byte[] signature = hmac(storedKey, authMessage.getBytes(StandardCharsets.UTF_8));
                byte[] proof = new byte[clientKey.length];
                for (int i = 0; i < proof.length; i++) proof[i] = (byte) (clientKey[i] ^ signature[i]);
                return Base64.getEncoder().encodeToString(proof);
            } catch (Exception e) {
                throw new IOException("SCRAM proof computation failed: " + e.getMessage(), e);
            }
        }

        void verifyServer(String serverFinal) throws IOException {
            if (!serverFinal.startsWith("v=")) {
                throw new IOException("Malformed SCRAM server-final message");
            }
            try {
                byte[] serverKey = hmac(saltedPassword, "Server Key".getBytes(StandardCharsets.US_ASCII));
                byte[] expected = hmac(serverKey, authMessage.getBytes(StandardCharsets.UTF_8));
                byte[] actual = Base64.getDecoder().decode(serverFinal.substring(2));
                if (!MessageDigest.isEqual(expected, actual)) {
                    throw new IOException("SCRAM server signature mismatch — possible MITM, aborting");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed SCRAM server-final message: " + e.getMessage(), e);
            }
        }

        private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations) throws IOException {
            try {
                SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                return f.generateSecret(new PBEKeySpec(
                        new String(password, StandardCharsets.UTF_8).toCharArray(), salt, iterations, 256)).getEncoded();
            } catch (Exception e) {
                throw new IOException("SCRAM PBKDF2 failed: " + e.getMessage(), e);
            }
        }

        private static byte[] hmac(byte[] key, byte[] data) throws IOException {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                return mac.doFinal(data);
            } catch (Exception e) {
                throw new IOException("SCRAM HMAC failed: " + e.getMessage(), e);
            }
        }
    }

    static String saslEscape(String name) {
        return name.replace("=", "=3D").replace(",", "=2C");
    }

    // ── message helpers ──────────────────────────────────────────────

    private static String[] parseDataRow(byte[] payload) throws IOException {
        if (payload.length < 2) throw new IOException("Truncated data row");
        int cols = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        String[] row = new String[cols];
        int pos = 2;
        for (int i = 0; i < cols; i++) {
            if (pos + 4 > payload.length) throw new IOException("Truncated data row");
            int len = ((payload[pos] & 0xFF) << 24) | ((payload[pos + 1] & 0xFF) << 16)
                    | ((payload[pos + 2] & 0xFF) << 8) | (payload[pos + 3] & 0xFF);
            pos += 4;
            if (len == -1) {
                row[i] = null;
            } else {
                if (pos + len > payload.length) throw new IOException("Truncated data row");
                row[i] = new String(payload, pos, len, StandardCharsets.UTF_8);
                pos += len;
            }
        }
        return row;
    }

    private static String nulString(byte[] payload, int from) {
        int end = from;
        while (end < payload.length && payload[end] != 0) end++;
        return new String(payload, from, end - from, StandardCharsets.UTF_8);
    }

    private static String serverMessage(byte[] payload) {
        // Error/Notice: sequence of (field-type, nul-terminated string) pairs.
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos < payload.length) {
            char field = (char) payload[pos++];
            String val = nulString(payload, pos);
            pos += val.getBytes(StandardCharsets.UTF_8).length + 1;
            if (field == 'M') sb.append(val);
        }
        String msg = sb.toString();
        return msg.isEmpty() ? ("raw: " + new String(payload, StandardCharsets.UTF_8)) : msg;
    }

    private static String randomNonce(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }
}
