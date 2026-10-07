package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import com.personalab.vectoract.vector_act_was.global.error.*;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

/**
 * A10의 업무 규칙을 담당합니다. HTTP 요청/응답이나 쿠키를 알지 못합니다.
 * Controller가 전달한 회원 UUID와 재인증 토큰을 검사하고 Repository를 통해 DB를 변경합니다.
 * 여기서는 Soft Delete만 수행합니다. 영상 삭제나 users 행의 DELETE는 호출하지 않습니다.
 */
@Service
public class WithdrawalService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuthOneTimeTokenRepository oneTimeTokens;
    private final RefreshTokenGenerator generator;

    public WithdrawalService(UserRepository users, RefreshTokenRepository refreshTokens,
                             AuthOneTimeTokenRepository oneTimeTokens, RefreshTokenGenerator generator) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.oneTimeTokens = oneTimeTokens;
        this.generator = generator;
    }

    // 트랜잭션은 아래 변경들을 '모두 성공 또는 모두 취소'로 묶습니다.
    // 예: 회원 상태 저장이 실패하면 앞서 소비한 재인증 토큰도 다시 미사용 상태로 돌아갑니다.
    // 메서드 실행 전 Spring이 시작하고, 정상 종료 후 커밋하며, RuntimeException이면 롤백합니다.
    @Transactional
    public Result withdraw(UUID userId, String rawToken) {
        // 로그인/재발급/A09와 같은 회원 행을 잠급니다. 동시 탈퇴도 한 요청만 성공합니다.
        var user = users.lockById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 중복 탈퇴가 기존 deleted_at/purge_at을 덮어써 보관 기간을 늘리지 않도록 먼저 거절합니다.
        if (user.getAccountStatus() == User.AccountStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.ACCOUNT_DELETED);
        }
        // 시각을 한 번만 구해 탈퇴 시각과 토큰 폐기 시각을 일치시킵니다.
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // A09의 조건부 UPDATE를 재사용합니다. 원문 대신 SHA-256 해시만 DB와 비교합니다.
        // 재인증 오류는 Access JWT 오류(401)와 구분하여 A10 명세의 403으로 응답합니다.
        // A09 토큰 원문은 32바이트 난수의 Base64URL 문자열(43자)입니다.
        // ||는 앞 조건이 참이면 뒤를 실행하지 않아 null을 해시하거나 불필요하게 DB를 조회하지 않습니다.
        // Repository의 반환값 1은 조건에 맞는 토큰 하나를 소비했다는 뜻, 0은 사용 불가라는 뜻입니다.
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")
                || oneTimeTokens.consumeIfUsable(generator.hash(rawToken), userId,
                    AuthOneTimeToken.TokenType.REAUTH, now) != 1) {
            throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
        }
        // 관리 중인 User 엔티티에 WITHDRAWN, deleted_at, purge_at=now+7일을 설정합니다.
        // JPA가 변경을 추적하므로 별도 users.save() 없이도 UPDATE가 실행됩니다.
        user.withdraw(now);
        // 모든 기기/로그인 계보의 미폐기 Refresh Token을 폐기합니다. 행 자체는 7일 동안 보관합니다.
        refreshTokens.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(now));
        // 방금 쓴 토큰 외의 재인증/비밀번호 재설정 토큰도 더 이상 사용하지 못하도록 표시합니다.
        oneTimeTokens.invalidateAll(userId, now);
        // 여기서 실패하면 회원 변경, 토큰 사용 표시, 토큰 폐기가 모두 롤백됩니다.
        // flush는 대기 중인 SQL을 보내는 단계입니다. 최종 커밋은 메서드 종료 후 Spring이 담당합니다.
        users.flush();
        return new Result(user.getDeletedAt(), user.getPurgeAt());
    }

    // 엔티티 대신 필요한 값만 전달하는 내부 결과입니다. Controller가 HTTP 응답 DTO로 변환합니다.
    public record Result(OffsetDateTime deletedAt, OffsetDateTime purgeAt) {}
}
