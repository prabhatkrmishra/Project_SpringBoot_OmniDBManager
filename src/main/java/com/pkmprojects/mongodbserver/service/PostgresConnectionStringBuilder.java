package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.ConnectionEndpoint;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Builds URI + JDBC from a structured {@link ConnectionEndpoint}.
 * The endpoint already carries the correct public host/port — no string
 * replacement to switch between direct/pooled.
 */
@Component
public class PostgresConnectionStringBuilder {
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
