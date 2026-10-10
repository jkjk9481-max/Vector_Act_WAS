package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * C03 실시간 코칭 연결 티켓(JWT)에 서명합니다. AI 서버 설계서 7.2의 규격을 따릅니다.
 *
 * <p>왜 별도 키쌍(비대칭)인가: 애플리케이션 Access Token은 서버 비밀키(HS256, 대칭)로 서명하지만, 이 티켓은
 * AI 서버가 검증해야 합니다. 대칭키를 AI 서버에 나눠 주면 AI 서버도 티켓을 위조할 수 있게 되므로,
 * 개인키는 애플리케이션 서버만 갖고 AI 서버에는 공개키만 배포합니다(AI 서버는 "검증만" 가능).
 *
 * <p>설정: {@code coaching.connection-ticket.private-key}(PKCS#8 PEM, RSA). 비어 있으면 티켓을 발급할 수 없어
 * {@link #isConfigured()}가 false를 반환하고, 호출한 쪽이 503으로 응답합니다. 잘못된 키 형식이면 기동에 실패합니다.
 */
@Component
public class ConnectionTicketSigner {
    /** 티켓 유효 시간(초). API 명세서 C03: 30초 유효. */
    public static final long TTL_SECONDS = 30;
    // aud 클레임 값. AI 서버가 "자기 전용 티켓인지" 확인하는 데 씁니다.
    private static final String AUDIENCE = "ai-server";

    // null이면 미설정 상태입니다.
    private final RSASSASigner signer;
    private final String keyId;

    public ConnectionTicketSigner(
            @Value("${coaching.connection-ticket.private-key:}") String privateKeyPem,
            @Value("${coaching.connection-ticket.key-id:}") String configuredKeyId) {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            this.signer = null;
            this.keyId = null;
            return;
        }
        try {
            RSAPrivateCrtKey privateKey = parsePrivateKey(privateKeyPem);
            // 서명 객체와 kid 계산에 쓸 공개키는 개인키(CRT 형식)에 들어 있는 modulus와 공개 지수로 복원합니다.
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            this.signer = new RSASSASigner(privateKey);
            // kid가 설정되지 않으면 공개키의 RFC 7638 지문을 씁니다. 키가 바뀌면 kid도 자동으로 바뀌어
            // AI 서버가 "어느 공개키로 검증할지" 식별할 수 있습니다.
            this.keyId = configuredKeyId == null || configuredKeyId.isBlank()
                    ? new RSAKey.Builder(publicKey).build().computeThumbprint().toString()
                    : configuredKeyId.strip();
        } catch (Exception exception) {
            // 키 원문이 로그에 남지 않도록 예외 원인을 붙이지 않습니다.
            throw new IllegalStateException("coaching.connection-ticket.private-key is invalid (RSA PKCS#8 PEM required)");
        }
    }

    public boolean isConfigured() {
        return signer != null;
    }

    /** 발급된 티켓과 만료 시각을 함께 담습니다. 응답의 expiresAt은 JWT의 exp와 같은 값입니다. */
    public record Ticket(String token, Instant expiresAt) {
        // 로그나 예외 메시지에 티켓 원문이 출력되지 않도록 가립니다.
        @Override
        public String toString() { return "Ticket[token=REDACTED]"; }
    }

    /**
     * 티켓을 발급합니다.
     *
     * @param userId    세션 소유자(sub 클레임)
     * @param sessionId 연결할 세션(sid 클레임). AI 서버는 접속 경로의 sessionId와 일치하는지 확인합니다
     */
    public Ticket issue(UUID userId, UUID sessionId, Instant now) {
        if (signer == null) throw new IllegalStateException("connection ticket signer is not configured");
        // JWT의 시각은 초 단위입니다. 응답의 expiresAt과 exp가 어긋나지 않도록 먼저 초 단위로 맞춥니다.
        Instant issuedAt = Instant.ofEpochSecond(now.getEpochSecond());
        Instant expiresAt = issuedAt.plusSeconds(TTL_SECONDS);
        var claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                // jti: 티켓 고유 ID. AI 서버가 사용한 jti를 만료 시까지 기억해 1회만 쓰이게 합니다.
                .jwtID(UUID.randomUUID().toString())
                .build();
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(keyId).build();
        var jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException exception) {
            throw new IllegalStateException("connection ticket signing failed");
        }
        return new Ticket(jwt.serialize(), expiresAt);
    }

    // PEM 텍스트에서 머리말·꼬리말과 공백을 제거하고 Base64로 디코딩해 PKCS#8 개인키로 읽습니다.
    // 환경변수에는 줄바꿈 대신 문자 그대로의 "\n"이 들어오는 경우가 많아 함께 처리합니다.
    private static RSAPrivateCrtKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
