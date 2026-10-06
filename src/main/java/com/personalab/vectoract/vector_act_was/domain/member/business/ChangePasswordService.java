package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class ChangePasswordService {
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwords;

    public ChangePasswordService(UserRepository users, RefreshTokenRepository tokens, PasswordEncoder passwords) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
    }

    // 비밀번호 저장과 모든 기기의 토큰 폐기는 함께 커밋하거나 함께 롤백합니다.
    // A03 로그인 및 A04 재발급과 같은 사용자 행을 잠급니다.
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, String confirmation) {
        var user = users.lockById(userId)
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }
        if (!newPassword.equals(confirmation)) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
        user.changePasswordHash(passwords.encode(newPassword));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        tokens.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(now));
        // JPA 변경 감지로 password_hash, updated_at, revoked_at을 저장합니다.
        tokens.flush();
    }
}
