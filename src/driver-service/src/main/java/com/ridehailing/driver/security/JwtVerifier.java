package com.ridehailing.driver.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T07 — verify JWT RS256 cho driver-service theo contract api-contracts.md §2:
 * allowlist alg {RS256} + chữ ký + iss = ridehailing-identity + aud chứa "driver-service"
 * (mọi phần tử aud phải là string) + temporal claims ({@code exp} bắt buộc, skew 0s;
 * {@code nbf} nếu có đã hiệu lực) + sub UUID + role hợp lệ + email không rỗng.
 * Mọi sai -> {@link UnauthorizedException} (401), KHÔNG thoát thành 500.
 * Pattern verify tái sử dụng từ identity-service (đã kiểm chứng bằng regression tests T06/F01).
 */
@Component
public class JwtVerifier {

    public static final String ISSUER = "ridehailing-identity";
    private static final String SERVICE_NAME = "driver-service";

    private final RSASSAVerifier verifier;

    public JwtVerifier(@Value("${jwt.public-key-path:keys/jwt-public.pem}") String publicKeyPath) {
        this.verifier = new RSASSAVerifier((java.security.interfaces.RSAPublicKey) loadPublicKey(Path.of(publicKeyPath)));
    }

    public AuthenticatedUser authenticate(String token) {
        JWSObject jws;
        try {
            jws = JWSObject.parse(token);
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
        if (!rawAud.contains(SERVICE_NAME)) {
            throw new UnauthorizedException("Token not issued for this service.");
        }

        // Temporal claims (F01/F02): exp bắt buộc + chưa hết hạn; nbf có mặt phải đã hiệu lực. Skew = 0s.
        // F02: NumericDate được phép mang phần giây lẻ — so sánh bằng double với now chính xác đến
        // mili giây, KHÔNG cắt xuống giây nguyên (exp = now + 0.5 s vẫn hợp lệ).
        // "nbf": null có mặt trong payload -> invalid (401), không bỏ qua.
        double nowEpochSeconds = Instant.now().toEpochMilli() / 1000.0;
        Object expRaw = claims.get("exp");
        if (!(expRaw instanceof Number expNum)) {
            throw new UnauthorizedException("Token 'exp' claim is missing or invalid.");
        }
        if (expNum.doubleValue() <= nowEpochSeconds) {
            throw new UnauthorizedException("Invalid or expired token.");
        }
        if (claims.containsKey("nbf")) {
            Object nbfRaw = claims.get("nbf");
            if (!(nbfRaw instanceof Number nbfNum)) {
                throw new UnauthorizedException("Token 'nbf' claim is invalid.");
            }
            if (nbfNum.doubleValue() > nowEpochSeconds) {
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

    /** Tải public key PEM X.509 — pattern đã kiểm chứng ở identity-service (T06/F01). */
    public static PublicKey loadPublicKey(Path path) {
        try {
            String b64 = Files.readString(path)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load JWT public key from " + path, e);
        }
    }
}
