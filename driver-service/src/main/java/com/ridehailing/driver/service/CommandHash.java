package com.ridehailing.driver.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Canonical body hashes are wrapped with both operation and resource identity. */
final class CommandHash {
    private CommandHash() {
    }

    static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }

    static String scoped(String operation, UUID reservationId, String bodyHash) {
        return sha256(operation + "|" + reservationId + "|" + bodyHash);
    }
}
