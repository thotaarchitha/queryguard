package dev.queryguard.common.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * First 16 hex chars of SHA-1. SHA-1 isn't here for security, just for a short,
     * stable key; 64 bits is plenty to tell query shapes apart.
     */
    public static String of(String normalizedSql) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(normalizedSql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 missing from this JVM", e);
        }
    }
}
