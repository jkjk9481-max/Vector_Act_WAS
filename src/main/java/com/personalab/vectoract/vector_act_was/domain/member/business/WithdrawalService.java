package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import com.personalab.vectoract.vector_act_was.global.error.*;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

/** A10: 회원 상태 변경과 모든 토큰 폐기를 하나의 트랜잭션으로 확정합니다. */
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

    @Transactional
    public Result withdraw(UUID userId, String rawToken) {
        // 로그인/재발급/A09와 같은 회원 행을 잠급니다. 동시 탈퇴도 한 요청만 성공합니다.
        var user = users.lockById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (user.getAccountStatus() == User.AccountStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.ACCOUNT_DELETED);
        }
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // A09의 조건부 UPDATE를 재사용합니다. 원문 대신 SHA-256 해시만 DB와 비교합니다.
        // 재인증 오류는 Access JWT 오류(401)와 구분하여 A10 명세의 403으로 응답합니다.
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")
                || oneTimeTokens.consumeIfUsable(generator.hash(rawToken), userId,
                    AuthOneTimeToken.TokenType.REAUTH, now) != 1) {
            throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
        }
        user.withdraw(now);
        refreshTokens.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(now));
        oneTimeTokens.invalidateAll(userId, now);
        // 여기서 실패하면 회원 변경, 토큰 사용 표시, 토큰 폐기가 모두 롤백됩니다.
        users.flush();
        return new Result(user.getDeletedAt(), user.getPurgeAt());
    }

    public record Result(OffsetDateTime deletedAt, OffsetDateTime purgeAt) {}
}
