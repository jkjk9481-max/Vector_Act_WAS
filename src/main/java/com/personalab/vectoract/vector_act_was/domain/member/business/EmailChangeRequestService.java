package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

@Service
public class EmailChangeRequestService {
    private final UserRepository users;
    private final AuthOneTimeTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final RefreshTokenGenerator generator;
    private final ApplicationEventPublisher events;

    public EmailChangeRequestService(UserRepository users, AuthOneTimeTokenRepository tokens,
            PasswordEncoder passwords, RefreshTokenGenerator generator, ApplicationEventPublisher events) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.generator = generator;
        this.events = events;
    }

    @Transactional
    public void request(UUID userId, String newEmail, String currentPassword) {
        // 같은 회원의 재요청과 탈퇴를 직렬화해 미사용 변경 토큰이 여러 개 남지 않게 합니다.
        var user = users.lockById(userId)
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }
        String normalized = newEmail.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals(user.getEmail())) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (users.existsByEmail(normalized)) throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String rawToken = generator.generate();
        // 새 토큰 저장 실패 시 이전 토큰 무효화도 함께 롤백합니다. 다른 용도의 토큰은 유지합니다.
        tokens.invalidateByUserAndType(userId, AuthOneTimeToken.TokenType.EMAIL_CHANGE, now);
        var token = AuthOneTimeToken.createEmailChange(user, generator.hash(rawToken), normalized, now);
        tokens.saveAndFlush(token);
        events.publishEvent(new EmailChangeRequested(normalized, rawToken, token.getExpiresAt()));
    }
}
