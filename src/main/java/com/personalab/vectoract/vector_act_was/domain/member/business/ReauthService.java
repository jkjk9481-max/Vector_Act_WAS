package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A09의 업무 처리: 본인 계정 확인 → 현재 비밀번호 비교 → 재인증 토큰 생성 → 해시 저장.
 * 재인증은 탈퇴 의사 확인을 돕는 단계입니다. 여기서 계정 상태나 로그인 토큰을 변경하지 않습니다.
 */
@Service
public class ReauthService {
    private final UserRepository users;
    private final AuthOneTimeTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final RefreshTokenGenerator generator;

    // 생성자 주입: Spring이 Repository, BCrypt 인코더, 난수·해시 생성기를 연결합니다.
    // 이름이 RefreshTokenGenerator지만 기능은 256비트 난수 생성과 SHA-256이므로 안전하게 재사용합니다.
    // 실제 토큰 용도와 저장 테이블은 Refresh Token과 분리됩니다.
    public ReauthService(UserRepository users, AuthOneTimeTokenRepository tokens,
                         PasswordEncoder passwords, RefreshTokenGenerator generator) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.generator = generator;
    }

    // 조회와 토큰 저장을 하나의 쓰기 트랜잭션으로 묶습니다. DB 저장/커밋 실패 시 발급 결과를 반환하지 않습니다.
    @Transactional
    public Result issue(UUID userId, String password) {
        // 1. A08과 같은 회원 행을 잠가 비밀번호 변경과 재인증의 순서를 보장합니다.
        // ACTIVE가 아닌 회원이나 없는 회원은 공통 404로 처리합니다.
        var user = activeUser(userId);
        // 2. 평문과 BCrypt 해시를 matches로 비교합니다. 해시는 복호화하지 않습니다.
        // 72바이트가 넘으면 인코더에 전달하지 않고 현재 비밀번호 불일치로 처리합니다.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }
        // 3. JWT와 별개인 예측 불가능한 난수 토큰을 만듭니다. 클라이언트에만 원문을 전달합니다.
        String rawToken = generator.generate();
        var token = AuthOneTimeToken.createReauth(user, generator.hash(rawToken), OffsetDateTime.now(ZoneOffset.UTC));
        // 4. saveAndFlush는 INSERT를 즉시 실행합니다. flush 자체가 커밋은 아닙니다.
        tokens.saveAndFlush(token);
        // Service 메서드가 끝나고 Spring의 커밋까지 성공해야 Controller가 이 결과로 200을 만듭니다.
        return new Result(rawToken, token.getExpiresAt());
    }

    /**
     * 향후 탈퇴 Service에서 호출할 재인증 토큰 소비 기능입니다. 별도 HTTP API는 만들지 않습니다.
     * MANDATORY는 호출자가 시작한 트랜잭션이 반드시 있어야 한다는 뜻입니다.
     * 따라서 토큰 사용 표시와 실제 탈퇴 저장이 함께 커밋되고, 탈퇴 실패 시 함께 롤백됩니다.
     * Controller에서 직접 호출하거나 독립적으로 먼저 소비하면 안 됩니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeForWithdrawal(UUID userId, String rawToken) {
        activeUser(userId);
        // 토큰은 생성 시 32바이트를 Base64URL로 바꾼 43자 문자열입니다. 잘못된 형식은 즉시 거절합니다.
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(ErrorCode.ACCESS_INVALID);
        }
        // 만료 시각과 현재 시각이 같아도 만료입니다. DB 쿼리의 expiresAt > now 조건이 이를 보장합니다.
        int consumed = tokens.consumeIfUsable(generator.hash(rawToken), userId,
                AuthOneTimeToken.TokenType.REAUTH, OffsetDateTime.now(ZoneOffset.UTC));
        if (consumed != 1) {
            // 다른 회원·다른 용도·만료·사용 완료·없는 토큰은 모두 인증에 사용할 수 없습니다.
            throw new BusinessException(ErrorCode.ACCESS_INVALID);
        }
    }

    // private 보조 메서드입니다. 위 public 메서드가 시작하거나 참여한 트랜잭션 안에서 실행됩니다.
    private User activeUser(UUID userId) {
        return users.lockById(userId)
                .filter(user -> user.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // 내부 결과 객체도 로그에 토큰 원문이 나오지 않도록 기본 record의 toString을 재정의합니다.
    public record Result(String reauthToken, OffsetDateTime expiresAt) {
        @Override public String toString() { return "ReauthResult[token=REDACTED]"; }
    }
}
