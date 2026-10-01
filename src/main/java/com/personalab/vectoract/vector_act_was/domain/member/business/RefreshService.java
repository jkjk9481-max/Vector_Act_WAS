package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class RefreshService {
    private final RefreshTokenRepository tokens;
    private final RefreshTokenGenerator generator;
    private final AccessTokenProvider accessTokens;

    public RefreshService(RefreshTokenRepository tokens, RefreshTokenGenerator generator,
                          AccessTokenProvider accessTokens) {
        this.tokens = tokens;
        this.generator = generator;
        this.accessTokens = accessTokens;
    }

    // Only the reuse error commits family revocation. All storage/rotation failures roll back.
    @Transactional(noRollbackFor = ReusedTokenException.class)
    public LoginService.Result refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new BusinessException(ErrorCode.REFRESH_INVALID);
        String hash = generator.hash(rawToken);
        User user = tokens.lockOwnerByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_INVALID));
        RefreshToken token = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_INVALID));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!token.getExpiresAt().isAfter(now)) throw new BusinessException(ErrorCode.REFRESH_EXPIRED);
        if (token.getRevokedAt() != null) {
            tokens.findByFamilyIdAndRevokedAtIsNull(token.getFamilyId()).forEach(active -> active.revoke(now));
            tokens.flush();
            throw new ReusedTokenException();
        }
        if (user.getAccountStatus() != User.AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.REFRESH_INVALID);
        }
        String replacementRaw = generator.generate();
        RefreshToken replacement = tokens.saveAndFlush(token.successor(generator.hash(replacementRaw), now));
        token.rotateTo(replacement, now);
        tokens.flush();
        String access = accessTokens.issue(user.getId());
        return new LoginService.Result(access, replacementRaw, user.getId(), user.getName(),
                user.getEmail(), user.getCreatedAt());
    }

    public static final class ReusedTokenException extends BusinessException {
        private ReusedTokenException() { super(ErrorCode.REFRESH_REUSED); }
    }
}
