package com.ridehailing.identity.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.ridehailing.identity.config.JwtProperties;
import com.ridehailing.identity.domain.Role;
import com.ridehailing.identity.domain.User;
import com.ridehailing.identity.exception.UnauthorizedException;
import com.ridehailing.identity.security.AuthenticatedUser;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T06 — phát và verify JWT RS256 theo contract api-contracts.md §2.
 * Claims: sub=userId, email, role, iss=ridehailing-identity, aud=[5 services], iat/exp (giây epoch).
 * Dùng Nimbus JOSE JWT 10.x trực tiếp (Spring Security 7 đã bỏ Jwt.builder — ghi chú trong task log T06).
 */
@Service
public class JwtService {

    public static final String ISSUER = "ridehailing-identity";
    public static final List<String> AUDIENCE = List.of(
            "identity-service", "driver-service", "trip-service", "matching-service", "payment-service");

    private final RSASSASigner signer;
    private final RSASSAVerifier verifier;
    private final long expiresInSeconds;

    public JwtService(JwtProperties props) {
        this.expiresInSeconds = props.getExpiresInSeconds();
        PrivateKey privateKey = loadPrivateKey(Path.of(props.getPrivateKeyPath()));
        PublicKey publicKey = loadPublicKey(Path.of(props.getPublicKeyPath()));
        this.signer = new RSASSASigner(privateKey);
        this.verifier = new RSASSAVerifier((java.security.interfaces.RSAPublicKey) publicKey);
    }

    public String issue(User user) {
        long nowEpoch = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.getId().toString());
        claims.put("email", user.getEmail());
        claims.put("role", user.getRole().name());
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("iat", nowEpoch);
        claims.put("exp", nowEpoch + expiresInSeconds);
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        try {
            jws.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
        return jws.serialize();
    }

    /**
     * Verify JWT theo contract api-contracts.md §2:
     * chữ ký RS256 (allowlist alg) + iss + aud chứa identity-service + sub UUID + role hợp lệ
     * + temporal claims: {@code exp} bắt buộc, chưa hết hạn; {@code nbf} nếu có phải đã hiệu lực.
     *
     * Clock skew policy (chốt đợt sửa F01): **0 giây** — không tolerance; test dùng token ký thật
     * với exp/nbf cố định so với giờ hiện tại nên kết quả tất định, không cần inject clock.
     * Mọi sai -> UnauthorizedException (401); claims thiếu/sai kiểu KHÔNG được thoát thành 500.
     */
    public AuthenticatedUser authenticate(String token) {
        JWSObject jws;
        try {
            jws = JWSObject.parse(token); // parse ném ParseException (checked) + JOSEException
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid or expired token.");
        }
        // Allowlist thuật toán (contract §2): chỉ RS256
        if (!JWSAlgorithm.RS256.equals(jws.getHeader().getAlgorithm())) {
            throw new UnauthorizedException("Unsupported signing algorithm.");
        }
        try {
            if (!jws.verify(verifier)) {
                // verify() ở Nimbus 10 chỉ kiểm tra chữ ký JWS — temporal claims kiểm tra bên dưới
                throw new UnauthorizedException("Invalid or expired token.");
            }
        } catch (JOSEException e) {
            throw new UnauthorizedException("Invalid or expired token.");
        }

        Map<String, Object> claims;
        try {
            claims = jws.getPayload().toJSONObject();
        } catch (RuntimeException e) {
            throw new UnauthorizedException("Token payload must be a JSON object.");
        }
        if (claims == null) {
            throw new UnauthorizedException("Token payload must be a JSON object.");
        }
        if (!ISSUER.equals(claims.get("iss"))) {
            throw new UnauthorizedException("Invalid token issuer.");
        }
        // aud: contract §2 chốt là mảng; sai kiểu/missing -> 401 (không ClassCastException -> 500)
        Object audRaw = claims.get("aud");
        if (!(audRaw instanceof List<?> rawAud)) {
            throw new UnauthorizedException("Token audience claim is invalid.");
        }
        if (rawAud.stream().anyMatch(value -> !(value instanceof String))) {
            throw new UnauthorizedException("Token audience entries must be strings.");
        }
        if (!rawAud.contains("identity-service")) {
            throw new UnauthorizedException("Token not issued for this service.");
        }

        // Temporal claims (F01): exp bắt buộc + chưa hết hạn; nbf nếu có phải đã hiệu lực. Skew = 0s.
        long nowEpoch = Instant.now().getEpochSecond();
        Object expRaw = claims.get("exp");
        if (!(expRaw instanceof Number expNum)) {
            throw new UnauthorizedException("Token 'exp' claim is missing or invalid.");
        }
        if (expNum.longValue() <= nowEpoch) {
            throw new UnauthorizedException("Invalid or expired token.");
        }
        Object nbfRaw = claims.get("nbf");
        if (nbfRaw != null) {
            if (!(nbfRaw instanceof Number nbfNum)) {
                throw new UnauthorizedException("Token 'nbf' claim is invalid.");
            }
            if (nbfNum.longValue() > nowEpoch) {
                throw new UnauthorizedException("Invalid or expired token.");
            }
        }

        Object subRaw = claims.get("sub");
        UUID userId;
        try {
            userId = UUID.fromString(String.valueOf(subRaw));
        } catch (RuntimeException e) {
            throw new UnauthorizedException("Invalid token subject.");
        }
        Role role;
        try {
            role = Role.valueOf(String.valueOf(claims.get("role")));
        } catch (RuntimeException e) {
            throw new UnauthorizedException("Invalid token role claim.");
        }
        Object emailRaw = claims.get("email");
        if (!(emailRaw instanceof String emailStr) || emailStr.isBlank()) {
            throw new UnauthorizedException("Token 'email' claim is invalid.");
        }
        return new AuthenticatedUser(userId, emailStr, role);
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    /** Public để các service khác (T07+) tải public key verify; private key không bao giờ rời identity-service. */
    public static PrivateKey loadPrivateKey(Path path) {
        try {
            String b64 = Files.readString(path)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load JWT private key from " + path, e);
        }
    }

    public static PublicKey loadPublicKey(Path path) {
        try {
            String b64 = Files.readString(path)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load JWT public key from " + path, e);
        }
    }
}
