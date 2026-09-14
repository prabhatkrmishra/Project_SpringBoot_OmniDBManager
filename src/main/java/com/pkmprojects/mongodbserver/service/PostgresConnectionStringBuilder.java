package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.ConnectionEndpoint;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Builds URI + JDBC from a structured {@link ConnectionEndpoint}.
 * The endpoint already carries the correct public host/port — no string
 * replacement to switch between direct/pooled.
 *
 * <p>Bridge routing: {@link #toUriBridged}/{@link #toJdbcBridged} append
 * {@code options=-c omnidb.mode=<direct|pooled>} for the single-host
 * TLS-bridge proxy (routing directive only, never auth; stripped by the
 * proxy before PG/PgBouncer). Plain {@link #toUri}/{@link #toJdbc} never
 * carry the directive — legacy direct/pooled ports go straight to
 * PostgreSQL/PgBouncer, which must not see an unknown {@code omnidb.mode}.
 * {@code application_name=omnidb} stays non-routing in all shapes.
 *
 * <p>TLS-bridge channel binding (S-06 closure): the bridge terminates
 * client TLS, so {@code SCRAM-SHA-256-PLUS} bound to the client-facing
 * certificate can never verify against the bridge→backend leg. DIRECT
 * PostgreSQL advertises {@code PLUS} and clients with
 * {@code channel_binding=prefer} (libpq/pgJDBC default) or forced binding
 * (pg8000) fail with {@code SCRAM channel binding check failed}; the
 * pooled path only offers plain {@code SCRAM-SHA-256} so it is unaffected.
 * Bridged strings therefore carry {@code channel_binding=disable} so every
 * mainstream client negotiates ordinary {@code SCRAM-SHA-256}. The bridge
 * never claims end-to-end channel binding; {@code require} cleanly fails.
 */
@Component
public class PostgresConnectionStringBuilder {
    /** Routing directive consumed by the TLS-bridge proxy, stripped before PG/PgBouncer. */
    public static final String MODE_OPTION_DIRECT = "-c omnidb.mode=direct";
    public static final String MODE_OPTION_POOLED = "-c omnidb.mode=pooled";
    /**
     * SCRAM channel-binding selector for bridged strings. The TLS bridge
     * terminates client TLS, so PLUS binding cannot survive the second leg;
     * {@code disable} makes clients use plain {@code SCRAM-SHA-256}.
     * Accepted by libpq/psql, pgJDBC ({@code channelBinding}), Node pg
     * (ignored, PLUS off by default), and harmless to drivers that ignore
     * unknown URI parameters. Never use {@code require} on bridged strings.
     */
    public static final String CHANNEL_BINDING_DISABLE = "disable";

    public String toUri(ConnectionEndpoint ep) {
        String base = "postgresql://" + encode(ep.username()) + ":" + encode(ep.password())
                + "@" + ep.host() + "/" + encode(ep.database());
        return base + "?sslmode=" + ep.sslMode().wireValue() + "&application_name=omnidb";
    }

    public String toJdbc(ConnectionEndpoint ep) {
        return "jdbc:postgresql://" + ep.host() + "/" + encode(ep.database())
                + "?user=" + encode(ep.username()) + "&password=" + encode(ep.password())
                + "&sslmode=" + ep.sslMode().wireValue() + "&ApplicationName=omnidb";
    }

    /**
     * URI for the single-host bridge (appends the routing directive plus
     * {@code channel_binding=disable} — see the class docs for why PLUS
     * cannot work through a TLS-terminating bridge).
     */
    public String toUriBridged(ConnectionEndpoint ep) {
        return toUri(ep) + "&channel_binding=" + CHANNEL_BINDING_DISABLE
                + "&options=" + encode(modeOption(ep));
    }

    /**
     * JDBC for the single-host bridge (appends the routing directive plus
     * {@code channelBinding=disable} — pgJDBC spelling of the same selector).
     */
    public String toJdbcBridged(ConnectionEndpoint ep) {
        return toJdbc(ep) + "&channelBinding=" + CHANNEL_BINDING_DISABLE
                + "&options=" + encode(modeOption(ep));
    }

    static String modeOption(ConnectionEndpoint ep) {
        return ep.mode() == com.pkmprojects.mongodbserver.model.ConnectionMode.POOLED
                ? MODE_OPTION_POOLED : MODE_OPTION_DIRECT;
    }

    static String encode(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '.' || b == '_' || b == '~') out.append((char) b);
            else out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                    .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
        }
        return out.toString();
    }
}
