package com.personalab.vectoract.vector_act_was.global.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AccessTokenProviderTests {
    private final byte[] key = new byte[32];
    private final AccessTokenProvider provider;

    AccessTokenProviderTests() {
        new SecureRandom().nextBytes(key);
        provider = new AccessTokenProvider(Base64.getEncoder().encodeToString(key), "test-issuer");
    }

    @Test
    void validTokenRoundTrip() {
        UUID id = UUID.randomUUID();
        var jwt = provider.verify(provider.issue(id));
        assertThat(jwt.getSubject()).isEqualTo(id.toString());
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(900));
    }

    @Test
    void rejectsTamperingAndDifferentSigningKey() {
        String token = provider.issue(UUID.randomUUID());
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"attacker\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];
        assertThatThrownBy(() -> provider.verify(tampered)).isInstanceOf(JwtException.class);
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        var other = new AccessTokenProvider(Base64.getEncoder().encodeToString(otherKey), "test-issuer");
        assertThatThrownBy(() -> other.verify(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.verify("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredWrongIssuerWrongUseMissingExpiryAndInvalidSubject() {
        Instant now = Instant.now();
        for (var claims : new JwtClaimsSet[]{
                claims(now.minusSeconds(1000), now.minusSeconds(100), "test-issuer", "access", UUID.randomUUID().toString()),
                claims(now, now.plusSeconds(900), "other", "access", UUID.randomUUID().toString()),
                claims(now, now.plusSeconds(900), "test-issuer", "refresh", UUID.randomUUID().toString()),
                claims(now, null, "test-issuer", "access", UUID.randomUUID().toString()),
                claims(now, now.plusSeconds(900), "test-issuer", "access", "invalid"),
                claims(now, now.plusSeconds(901), "test-issuer", "access", UUID.randomUUID().toString())}) {
            assertThatThrownBy(() -> provider.verify(sign(claims))).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsMissingWeakOrMalformedKeys() {
        for (String secret : new String[]{"", "invalid!", Base64.getEncoder().encodeToString(new byte[16])}) {
            assertThatThrownBy(() -> new AccessTokenProvider(secret, "test")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private JwtClaimsSet claims(Instant issued, Instant expires, String issuer, String use, String subject) {
        var builder = JwtClaimsSet.builder().issuer(issuer).subject(subject).issuedAt(issued).claim("token_use", use);
        if (expires != null) builder.expiresAt(expires);
        return builder.build();
    }

    private String sign(JwtClaimsSet claims) {
        var encoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }
}
