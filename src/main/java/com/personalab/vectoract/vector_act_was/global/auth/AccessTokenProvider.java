package com.personalab.vectoract.vector_act_was.global.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** JWT 서명과 검증을 담당합니다. 토큰을 DB에 저장하지 않습니다. */
@Component
public class AccessTokenProvider {
    public static final String EXPIRED_ERROR = "access_expired";
    public static final long EXPIRES_IN = 900;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final String issuer;

    public AccessTokenProvider(@Value("${auth.jwt.secret-base64}") String secretBase64,
                               @Value("${auth.jwt.issuer}") String issuer) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("JWT_SECRET_BASE64는 Base64 형식이어야 합니다.");
        }
        if (keyBytes.length < 32 || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT 키는 최소 32바이트이고 issuer는 필수입니다.");
        }
        var key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.issuer = issuer;
        this.encoder = NimbusJwtEncoder.withSecretKey(key).build();
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // 서명뿐 아니라 만료 시각, 발급자, 용도, 회원 식별자도 확인합니다.
        // 만료 시각 이후에 추가 허용 시간을 두지 않습니다.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                this::validateTimestamps, new JwtIssuerValidator(issuer), this::validateClaims));
    }

    public String issue(UUID userId) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(EXPIRES_IN))
                .id(UUID.randomUUID().toString()).claim("token_use", "access").build();
        // HS256은 환경변수의 비밀키로 서명합니다. JWT에는 비밀번호나 개인정보를 넣지 않습니다.
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }

    public Jwt verify(String token) {
        // 서명 검증 후 claim을 검사합니다. 인증 필터는 검증 오류 코드로 만료와 다른 오류를 구분합니다.
        return decoder.decode(token);
    }

    private OAuth2TokenValidatorResult validateTimestamps(Jwt jwt) {
        var result = new JwtTimestampValidator(Duration.ZERO).validate(jwt);
        Instant now = Instant.now();
        // 서명 검증을 통과한 JWT만 여기 도달합니다. 예외 메시지나 미검증 payload로 만료를 판단하지 않습니다.
        // nbf(사용 가능 시작 시각)까지 잘못되었다면 단순 만료가 아니므로 기존 invalid_token을 유지합니다.
        if (result.hasErrors() && jwt.getExpiresAt() != null && now.isAfter(jwt.getExpiresAt())
                && (jwt.getNotBefore() == null || !now.isBefore(jwt.getNotBefore()))) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(EXPIRED_ERROR, "Access token expired", null));
        }
        return result;
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
            if ("access".equals(jwt.getClaimAsString("token_use")) && jwt.getIssuedAt() != null
                    && jwt.getExpiresAt() != null && !jwt.getIssuedAt().isAfter(Instant.now())
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(EXPIRES_IN))) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // 누락되거나 잘못된 claim도 검증 실패로 처리합니다.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access claims", null));
    }
}
