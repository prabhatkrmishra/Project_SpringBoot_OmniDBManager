package com.pkmprojects.mongodbserver.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide unique PostgreSQL role names for managed databases.
 * PG roles are cluster-global, so {@code dbA/user=app + dbB/user=app} would
 * collide on one role. New databases get {@code omni_<sanitizedDb>_<rand6>}.
 * Legacy databases keep their existing {@code userName} (compat shim — see
 * {@link #isLegacyCompatible(String, String)}); rotation/deletion always use
 * the stored {@code ManagedDatabase.userName}, never re-derive.
 */
@Component
public class PostgresRoleNameGenerator {
    private static final Pattern SAFE = Pattern.compile("[a-z0-9_]+");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private final SecureRandom random = new SecureRandom();

    public String generate(String dbName) {
        String base = dbName == null ? "db" : dbName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (base.isBlank()) base = "db";
        if (base.length() > 24) base = base.substring(0, 24);
        if (!SAFE.matcher(base).matches()) base = "db";
        return "omni_" + base + "_" + suffix(6);
    }

    /**
     * S-08 P1: same scheme with a total-length cap (MySQL usernames are
     * limited to 32 chars: {@code omni_} + base + {@code _} + 6-char suffix
     * leaves 20 for the base).
     */
    public String generate(String dbName, int maxLength) {
        String base = dbName == null ? "db" : dbName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (base.isBlank()) base = "db";
        int baseCap = Math.max(1, Math.min(24, maxLength - 12));
        if (base.length() > baseCap) base = base.substring(0, baseCap);
        if (!SAFE.matcher(base).matches()) base = "db";
        return "omni_" + base + "_" + suffix(6);
    }

    private String suffix(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Legacy rows (pre-uniqueness) used raw user input as role — still valid, just not generatable. */
    public static boolean isLegacyCompatible(String storedUser, String dbName) {
        return storedUser != null && !storedUser.isBlank();
    }
}
