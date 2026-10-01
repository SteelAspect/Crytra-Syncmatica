package com.steelaspect.cytrasyncmatica.bridge;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One-time codes for account linking: {@code /cytra-syncmatica link} in game
 * hands the player a code, {@code /link <code>} in Discord lets the bot claim
 * it through the bridge and learn the player's UUID. Codes live 10 minutes, one
 * per player at a time, in memory only.
 */
public final class LinkCodes {
    public static final long TTL_MILLIS = 10 * 60_000L;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> codes = new HashMap<>();

    public record Entry(UUID uuid, String name, long expiresAt) {
    }

    public record Claim(UUID uuid, String name) {
    }

    public synchronized String issue(final UUID uuid, final String name) {
        final long now = System.currentTimeMillis();
        purge(now);
        codes.values().removeIf(e -> e.uuid().equals(uuid));
        String code;
        do {
            final StringBuilder sb = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            code = sb.toString();
        } while (codes.containsKey(code));
        codes.put(code, new Entry(uuid, name, now + TTL_MILLIS));
        return code;
    }

    /** Consumes the code; null when unknown or expired. */
    public synchronized Claim claim(final String raw) {
        final long now = System.currentTimeMillis();
        purge(now);
        if (raw == null) {
            return null;
        }
        final Entry e = codes.remove(raw.trim().toUpperCase(Locale.ROOT).replace("0", "O").replace("1", "I"));
        return e == null ? null : new Claim(e.uuid(), e.name());
    }

    private void purge(final long now) {
        final Iterator<Entry> it = codes.values().iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt() <= now) {
                it.remove();
            }
        }
    }

    public synchronized int size() {
        return codes.size();
    }
}
