package com.ridehailing.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình JWT (contract api-contracts.md §2).
 * Private key chỉ nằm ở identity-service; public key phát cho các service khác (T07).
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** PEM PKCS#8 RSA private key — chỉ identity-service đọc. */
    private String privateKeyPath = "keys/jwt-private.pem";

    /** PEM X.509 RSA public key — dùng để verify token (cả /me và các service khác ở T07). */
    private String publicKeyPath = "keys/jwt-public.pem";

    /** Thời hạn token, giây (env JWT_EXPIRES_IN_SECONDS, default 3600). */
    private long expiresInSeconds = 3600L;

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }
    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String publicKeyPath) { this.publicKeyPath = publicKeyPath; }
    public long getExpiresInSeconds() { return expiresInSeconds; }
    public void setExpiresInSeconds(long expiresInSeconds) { this.expiresInSeconds = expiresInSeconds; }
}
