package com.personalab.vectoract.vector_act_was.global.auth;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class RefreshTokenGenerator {
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        // 예측할 수 없는 256비트 난수입니다. URL/쿠키에 안전한 문자열로 바꿉니다.
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String rawToken) {
        try {
            // 비밀번호와 달리 충분히 긴 난수이므로 SHA-256 해시로 원문 저장을 피합니다.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
