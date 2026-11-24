package com.nexus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class JobRules {
    private JobRules() {}

    public static long retryDelaySeconds(int attempts) {
        if (attempts < 1 || attempts > 5) throw new IllegalArgumentException("invalid attempt count");
        return 1L << attempts;
    }

    public static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int wordCount(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? 0 : stripped.split("(?U)\\s+").length;
    }
}
