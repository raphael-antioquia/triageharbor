package com.triageharbor.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class EventFingerprintV1 {

    static final int VERSION = 1;

    private EventFingerprintV1() {}

    static String compute(String exceptionType, String message) {
        MessageDigest digest = sha256();

        updateField(digest, exceptionType);
        updateField(digest, message);

        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateField(MessageDigest digest, String value) {
        if (value == null) {
            digest.update("-1:".getBytes(StandardCharsets.US_ASCII));
            return;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
