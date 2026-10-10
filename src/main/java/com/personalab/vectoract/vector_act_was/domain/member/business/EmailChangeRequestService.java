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

/**
 * A13 이메일 변경 요청의 업무 규칙입니다.
 *
 * <p>전체 흐름(A13 → 메일 → A14):
 * <ol>
 *   <li>A13(이 클래스): 로그인한 회원이 현재 비밀번호로 본인임을 증명하고 새 이메일을 알립니다.
 *       서버는 일회용 토큰을 만들어 <b>해시만</b> DB에 저장하고, 원문은 새 이메일로 보낼 메일에만 담습니다.</li>
 *   <li>회원이 새 이메일 수신함에서 링크를 엽니다(이 메일 주소의 소유를 증명).</li>
 *   <li>A14({@link EmailChangeCompletionService}): 토큰을 소비하면서 이메일을 실제로 바꿉니다.</li>
 * </ol>
 * 이 단계에서는 users.email을 바꾸지 않습니다. 확인 전에는 기존 이메일이 그대로 유지됩니다.
 */
@Service
public class EmailChangeRequestService {
    private final UserRepository users;
    private final AuthOneTimeTokenRepository tokens;
    private final PasswordEncoder passwords;
    // 토큰 원문 생성(256비트 난수)과 SHA-256 해시를 담당합니다. DB에는 해시만 저장합니다.
    private final RefreshTokenGenerator generator;
    // 메일 발송을 직접 호출하지 않고 이벤트로 알립니다. 발송은 커밋이 끝난 뒤 리스너가 처리합니다.
    private final ApplicationEventPublisher events;

    public EmailChangeRequestService(UserRepository users, AuthOneTimeTokenRepository tokens,
            PasswordEncoder passwords, RefreshTokenGenerator generator, ApplicationEventPublisher events) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.generator = generator;
        this.events = events;
    }

    /**
     * 이메일 변경 토큰을 발급하고 발송 이벤트를 발행합니다.
     * 검사 순서: 회원 상태 → 현재 비밀번호 → 새 이메일(현재와 동일 / 이미 사용 중) → 토큰 발급.
     */
    @Transactional
    public void request(UUID userId, String newEmail, String currentPassword) {
        // 같은 회원의 재요청과 탈퇴를 직렬화해 미사용 변경 토큰이 여러 개 남지 않게 합니다.
        // ACTIVE가 아닌 회원(탈퇴 등)은 구분되지 않는 404로 처리합니다.
        var user = users.lockById(userId)
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // BCrypt는 입력의 앞 72바이트만 사용합니다. 72바이트를 넘는 입력은 비교 자체를 하지 않고 거절해,
        // 뒷부분만 다른 비밀번호가 통과하는 일을 막습니다.
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }
        // 이메일은 앞뒤 공백 제거 + 소문자로 정규화해 저장·비교합니다(대소문자만 다른 중복 가입 방지).
        String normalized = newEmail.strip().toLowerCase(Locale.ROOT);
        // 현재 이메일과 같은 주소로 바꾸는 요청은 의미가 없으므로 입력 오류입니다.
        if (normalized.equals(user.getEmail())) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        // 다른 회원(탈퇴 대기 회원 포함)이 이미 쓰는 주소는 거절합니다. 확정 시점(A14)에 한 번 더 검사합니다.
        if (users.existsByEmail(normalized)) throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String rawToken = generator.generate();
        // 새 토큰 저장 실패 시 이전 토큰 무효화도 함께 롤백합니다. 다른 용도의 토큰은 유지합니다.
        // (무효화를 먼저 하는 이유: 회원당 사용 가능한 변경 토큰을 항상 가장 최근 것 하나로 유지하기 위해서)
        tokens.invalidateByUserAndType(userId, AuthOneTimeToken.TokenType.EMAIL_CHANGE, now);
        var token = AuthOneTimeToken.createEmailChange(user, generator.hash(rawToken), normalized, now);
        tokens.saveAndFlush(token);
        // 이벤트 안의 토큰 원문은 커밋 후 리스너가 메일 링크를 만들 때만 사용합니다. 응답·로그에는 나가지 않습니다.
        events.publishEvent(new EmailChangeRequested(normalized, rawToken, token.getExpiresAt()));
    }
}
