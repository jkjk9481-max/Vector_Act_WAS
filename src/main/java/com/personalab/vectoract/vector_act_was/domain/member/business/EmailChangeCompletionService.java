package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * A14: A13에서 발급한 EMAIL_CHANGE 토큰을 소비하고 이메일을 확정합니다.
 * 토큰 소비, 이메일 변경, 모든 Refresh Token 폐기는 하나의 트랜잭션입니다.
 */
@Service
public class EmailChangeCompletionService {
    private final UserRepository users;
    private final AuthOneTimeTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenGenerator generator;

    public EmailChangeCompletionService(UserRepository users, AuthOneTimeTokenRepository tokens,
                                        RefreshTokenRepository refreshTokens, RefreshTokenGenerator generator) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.generator = generator;
    }

    @Transactional
    public void complete(String rawToken) {
        String hash = generator.hash(rawToken);
        var token = tokens.findByTokenHash(hash)
                .filter(found -> found.getTokenType() == AuthOneTimeToken.TokenType.EMAIL_CHANGE)
                .filter(found -> found.getNewEmail() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_CHANGE_TOKEN_INVALID));
        // 로그인·탈퇴·A13 재요청과 같은 회원 행을 잠가 서로의 중간 상태를 보지 않게 합니다.
        var user = users.lockById(token.getUser().getId())
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_CHANGE_TOKEN_INVALID));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // 조건부 UPDATE가 소유자·용도·미사용·만료를 함께 검사하므로 동시 재사용은 한 번만 성공합니다.
        if (tokens.consumeIfUsable(hash, user.getId(), AuthOneTimeToken.TokenType.EMAIL_CHANGE, now) != 1) {
            throw new BusinessException(ErrorCode.EMAIL_CHANGE_TOKEN_INVALID);
        }
        // A13 이후 다른 회원이 같은 주소를 사용했을 수 있어 확정 시점에 다시 검사합니다.
        // 예외가 나면 위의 토큰 소비도 롤백되어 토큰은 만료 전까지 유지됩니다.
        String newEmail = token.getNewEmail();
        if (users.existsByEmail(newEmail)) throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        user.changeEmail(newEmail);
        try {
            users.flush();
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 주소를 확정한 다른 회원은 사전 검사를 모두 통과할 수 있어 DB 제약도 처리합니다.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
                }
            }
            throw e;
        }
        // 모든 기기에서 재로그인하도록 이 회원의 미폐기 Refresh Token을 모두 폐기합니다.
        // 기존 Access JWT는 만료 시각까지 유효합니다(A08과 같은 정책).
        refreshTokens.findByUserIdAndRevokedAtIsNull(user.getId()).forEach(t -> t.revoke(now));
        refreshTokens.flush();
    }
}
