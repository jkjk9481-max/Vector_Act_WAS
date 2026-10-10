package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.Locale;

@Service
public class PasswordResetRequestService {
    private final UserRepository users;
    private final AuthOneTimeTokenRepository tokens;
    private final RefreshTokenGenerator generator;
    private final ApplicationEventPublisher events;

    public PasswordResetRequestService(UserRepository users, AuthOneTimeTokenRepository tokens,
            RefreshTokenGenerator generator, ApplicationEventPublisher events) {
        this.users = users;
        this.tokens = tokens;
        this.generator = generator;
        this.events = events;
    }

    @Transactional
    public void request(String email) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        // 탈퇴 처리와 같은 행을 잠가 상태 확인과 발급 사이의 경쟁을 막습니다.
        var user = users.lockByEmail(normalized).orElse(null);
        if (user == null || user.getAccountStatus() != User.AccountStatus.ACTIVE) return;
        String raw = generator.generate();
        var token = AuthOneTimeToken.createPasswordReset(user, generator.hash(raw), OffsetDateTime.now(ZoneOffset.UTC));
        tokens.saveAndFlush(token);
        // 원문은 커밋 후 전달에만 사용하며 저장 실패 시 발송하지 않습니다.
        events.publishEvent(new PasswordResetRequested(user.getEmail(), raw, token.getExpiresAt()));
    }
}
