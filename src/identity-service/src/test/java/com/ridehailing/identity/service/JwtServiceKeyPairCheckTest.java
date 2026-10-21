package com.ridehailing.identity.service;

import com.ridehailing.identity.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F26 — JwtService phải dừng ngay khi khởi động nếu cặp key cấu hình lệch
 * (private/public không khớp): ký probe bằng private rồi verify bằng public.
 */
class JwtServiceKeyPairCheckTest {

    private static String writePem(Path dir, String name, byte[] encoded, boolean isPrivate) throws Exception {
        Path p = dir.resolve(name);
        String kind = isPrivate ? "PRIVATE" : "PUBLIC";
        Files.writeString(p, "-----BEGIN " + kind + " KEY-----\n"
                + Base64.getEncoder().encodeToString(encoded)
                + "\n-----END " + kind + " KEY-----\n");
        return p.toString();
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    @Test
    void mismatchedPublicKeyFailsStartup(@TempDir Path dir) throws Exception {
        KeyPair a = newKeyPair();
        KeyPair b = newKeyPair(); // cặp khác — public của b không khớp private của a

        JwtProperties props = new JwtProperties();
        props.setPrivateKeyPath(writePem(dir, "priv.pem", a.getPrivate().getEncoded(), true));
        props.setPublicKeyPath(writePem(dir, "pub.pem", b.getPublic().getEncoded(), false));

        assertThatThrownBy(() -> new JwtService(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key pair mismatch");
    }

    @Test
    void matchingKeyPairStartsUp(@TempDir Path dir) throws Exception {
        KeyPair a = newKeyPair();

        JwtProperties props = new JwtProperties();
        props.setPrivateKeyPath(writePem(dir, "priv.pem", a.getPrivate().getEncoded(), true));
        props.setPublicKeyPath(writePem(dir, "pub.pem", a.getPublic().getEncoded(), false));

        // Cặp khớp — khởi động thành công (không ném exception).
        new JwtService(props);
    }
}
