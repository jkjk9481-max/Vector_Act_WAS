package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

@Service
public class LoginService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final AccessTokenProvider accessTokens;
    private final RefreshTokenGenerator refreshGenerator;
    private final String dummyHash;

    public LoginService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwords,
                        AccessTokenProvider accessTokens, RefreshTokenGenerator refreshGenerator) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.refreshGenerator = refreshGenerator;
        // 없는 이메일도 BCrypt 비교를 수행해 계정 존재 여부에 따른 처리 시간 차이를 줄입니다.
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    /** Controller → 비밀번호 검증 → 토큰 발급 → Repository 저장 순서입니다. */
    @Transactional
    public Result login(String email, String password) {
        // 비밀번호 변경과 로그인 발급을 직렬화하여 이전 비밀번호로 새 토큰이 남지 않게 합니다.
        User user = users.lockByEmail(email.strip().toLowerCase(Locale.ROOT)).orElse(null);
        // 입력 비밀번호를 자르거나 변환하지 않습니다. BCrypt 한도 초과는 인증 실패로 처리합니다.
        boolean matches = password != null && !password.isEmpty()
                && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null || user.getAccountStatus() != User.AccountStatus.ACTIVE) {
            // 잘못된 비밀번호/없는 이메일/탈퇴 계정을 같은 오류로 응답합니다.
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        String accessToken = accessTokens.issue(user.getId());
        String rawRefreshToken = refreshGenerator.generate();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        refreshTokens.saveAndFlush(RefreshToken.create(user, refreshGenerator.hash(rawRefreshToken), now));
        // DB 커밋이 실패하면 Controller로 결과가 전달되지 않으므로 쿠키도 발급되지 않습니다.
        return new Result(accessToken, rawRefreshToken, user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }

    // 이 내부 결과를 그대로 JSON으로 반환하면 안 됩니다. Controller가 Refresh Token을 쿠키로 분리합니다.
    public record Result(String accessToken, String refreshToken, UUID userId, String name,
                         String email, OffsetDateTime createdAt) {

        @Override public String toString() {
            return "LoginResult[tokens=REDACTED]";
        }
    }
}
