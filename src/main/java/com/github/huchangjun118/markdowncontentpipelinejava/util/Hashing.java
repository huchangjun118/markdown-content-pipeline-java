package com.github.huchangjun118.markdowncontentpipelinejava.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Deterministic content hashing helpers.
 *
 * <p>Hashing only ever depends on the bytes explicitly passed in; wall-clock
 * time, locale, environment variables and iteration order of unordered
 * collections are never read. Callers that hash collections must sort their
 * elements first.
 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {
    }

    /** SHA-256 hex of a UTF-8 string. */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Stable hash over a sequence of already-hashed parts. Each part is
     * length-prefixed so concatenation cannot introduce ambiguities.
     */
    public static String combine(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(part == null ? "" : part).append(':');
        }
        return sha256(sb.toString());
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
