package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 비밀번호 변경의 업무 규칙을 담당합니다. HTTP 헤더나 쿠키는 다루지 않습니다.
 * 순서: 회원 조회·잠금 → 현재 비밀번호 확인 → 새 비밀번호 확인 → 해시 변경 → 전체 토큰 폐기.
 */
// Spring이 이 클래스를 관리하는 객체로 등록하여 Controller에 주입할 수 있게 합니다.
@Service
public class ChangePasswordService {
    // users는 회원 조회, tokens는 Refresh Token 조회·저장, passwords는 해시 생성·비교 담당입니다.
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwords;

    // Spring이 Repository 구현체와 PasswordEncoderConfig의 BCrypt 인코더를 전달합니다.
    public ChangePasswordService(UserRepository users, RefreshTokenRepository tokens, PasswordEncoder passwords) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
    }

    // 비밀번호 저장과 모든 기기의 토큰 폐기는 함께 커밋하거나 함께 롤백합니다.
    // A03 로그인 및 A04 재발급과 같은 사용자 행을 잠급니다.
    // @Transactional은 Spring이 이 메서드를 호출하기 전에 트랜잭션을 시작하게 합니다.
    // 메서드가 정상 종료하면 커밋(확정)하고, RuntimeException이 밖으로 전달되면 롤백(취소)합니다.
    // 쓰기 작업이므로 readOnly=true를 사용하지 않습니다. 사용자 잠금도 트랜잭션 종료 시 풀립니다.
    // Controller의 @Valid를 통과한 값을 받는 메서드이며, 아래에서는 업무 규칙을 검사합니다.
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, String confirmation) {
        // 1. UUID로 회원을 조회하면서 DB의 해당 사용자 행에 쓰기 잠금을 잡습니다.
        // 같은 회원을 잠그는 로그인·재발급·다른 비밀번호 변경은 이 트랜잭션이 끝날 때까지 기다립니다.
        // var는 컴파일러가 변수의 타입(User)을 추론하게 하는 문법입니다.
        var user = users.lockById(userId)
                // Optional의 filter는 조건에 맞지 않는 회원을 '결과 없음'으로 바꿉니다.
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                // 조회 결과가 없거나 탈퇴한 회원이면 404용 예외를 던지고 즉시 중단합니다.
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 2. BCrypt는 입력을 UTF-8 바이트로 처리하므로 현재 비밀번호도 72바이트 한도를 검사합니다.
        // ||는 앞 조건이 참이면 뒤를 실행하지 않습니다. 한도 초과 입력은 matches에 전달하지 않습니다.
        // matches는 평문 입력과 저장된 해시의 일치 여부를 검사하며 해시를 복호화하지 않습니다.
        // BCrypt는 매번 임의의 salt를 사용하므로 새로 encode한 문자열끼리 단순 비교하면 안 됩니다.
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }
        // 3. 현재 비밀번호가 맞은 뒤 확인 값을 검사합니다. String 내용 비교는 equals를 사용합니다.
        // 둘 다 잘못된 요청이면 위 단계에서 중단되어 CURRENT_PASSWORD_INVALID가 먼저 반환됩니다.
        if (!newPassword.equals(confirmation)) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
        // 4. 새 비밀번호를 복원이 불가능한 BCrypt 해시로 바꾸어 엔티티에 반영합니다.
        // 현재 트랜잭션이 관리하는 엔티티이므로 JPA가 변경을 추적합니다. 별도 save()는 필요 없습니다.
        user.changePasswordHash(passwords.encode(newPassword));
        // 5. 폐기 시각은 UTC로 한 번만 구하여 대상 토큰들에 같은 값을 기록합니다.
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // family(한 로그인에서 이어진 토큰 계보)를 제한하지 않고 이 회원의 미폐기 토큰 전부를 찾습니다.
        // forEach의 token -> ...는 조회된 토큰 하나마다 revoke(now)를 실행하는 람다입니다.
        // 행을 삭제하는 대신 revoked_at을 채워 폐기 이력을 남깁니다.
        tokens.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(now));
        // JPA 변경 감지로 password_hash, updated_at, revoked_at을 저장합니다.
        // flush()는 이 영속성 컨텍스트의 대기 중인 SQL을 DB에 보내므로 User 변경도 함께 반영됩니다.
        // flush는 커밋이 아닙니다. 여기서 또는 이후 커밋 단계에서 실패하면 전체 작업이 롤백됩니다.
        // User의 @PreUpdate 메서드가 SQL 갱신 전에 updated_at을 채웁니다.
        tokens.flush();
        // 메서드 본문이 끝난 뒤 Spring이 커밋합니다. 커밋 성공 후에만 Controller가 200을 만듭니다.
        // Refresh Token 폐기는 기존 Access JWT의 즉시 무효화를 뜻하지 않습니다. JWT는 만료까지 유효합니다.
    }
}
