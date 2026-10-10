package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class PasswordResetService {
    private final UserRepository users;
    private final AuthOneTimeTokenRepository tokens;
    private final RefreshTokenGenerator generator;
    private final PasswordEncoder passwords;

    public PasswordResetService(UserRepository users, AuthOneTimeTokenRepository tokens,
                                RefreshTokenGenerator generator, PasswordEncoder passwords) {
        this.users = users;
        this.tokens = tokens;
        this.generator = generator;
        this.passwords = passwords;
    }

    @Transactional
    public void reset(String rawToken, String newPassword, String confirmation) {
        if (!newPassword.equals(confirmation)) throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        String hash = generator.hash(rawToken);
        var token = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESET_TOKEN_INVALID));
        // 기존 인증 처리와 같은 사용자 행을 잠가 탈퇴 및 동시 변경과의 경쟁을 막습니다.
        var user = users.lockById(token.getUser().getId())
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESET_TOKEN_INVALID));
        // 소비와 비밀번호 저장은 같은 트랜잭션이며 조건부 UPDATE로 동시 재사용을 막습니다.
        if (tokens.consumeIfUsable(hash, user.getId(), AuthOneTimeToken.TokenType.PASSWORD_RESET,
                OffsetDateTime.now(ZoneOffset.UTC)) != 1) {
            throw new BusinessException(ErrorCode.RESET_TOKEN_INVALID);
        }
        user.changePasswordHash(passwords.encode(newPassword));
        users.flush();
    }
}
