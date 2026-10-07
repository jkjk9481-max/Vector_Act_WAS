package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.Locale;

/**
 * Controller → Service → Repository 중 업무 규칙과 트랜잭션을 담당합니다.
 * 회원이 없거나 탈퇴했어도 정상 종료합니다. 가입 여부를 Controller에 반환하지 않습니다.
 * A11은 토큰 발급만 담당하며, 비밀번호 변경·로그인 토큰 폐기·재설정 토큰 소비는 하지 않습니다.
 */
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
        // 1. 기존 가입/로그인 규칙과 같게 비교합니다. 메서드는 Controller에서 검증한 이메일을 받습니다.
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        // 2. 탈퇴와 같은 회원 행을 잠급니다. 탈퇴가 먼저 완료되면 아래 ACTIVE 검사에서 발급을 막습니다.
        var user = users.lockByEmail(normalized).orElse(null);
        // 정상 입력의 미가입/탈퇴 이메일에도 동일한 202를 반환하도록 예외를 던지지 않습니다.
        if (user == null || user.getAccountStatus() != User.AccountStatus.ACTIVE) return;

        // 3. 256비트 난수 원문을 만들고 DB에는 SHA-256 해시만 저장합니다.
        // 원문은 이메일 전달용 메모리에만 잠시 있으며 HTTP 응답과 로그에는 넣지 않습니다.
        String raw = generator.generate();
        var token = AuthOneTimeToken.createPasswordReset(user, generator.hash(raw), OffsetDateTime.now(ZoneOffset.UTC));
        tokens.saveAndFlush(token);
        // 4. 저장 실패 시 메일이 먼저 나가지 않도록 커밋 후에만 이벤트 수신기가 발송을 시도합니다.
        // flush는 커밋이 아닙니다. 실제 커밋 실패 시 AFTER_COMMIT 수신기는 실행되지 않습니다.
        events.publishEvent(new PasswordResetRequested(user.getEmail(), raw, token.getExpiresAt()));
        // 반복 요청 시 기존 토큰은 건드리지 않습니다. 임의 요청만으로 이전 링크를 무효화하지 않습니다.
        // 각 토큰은 독립적으로 15분 유효하며 향후 A12에서 미사용·용도·만료를 검사해야 합니다.
    }
}
