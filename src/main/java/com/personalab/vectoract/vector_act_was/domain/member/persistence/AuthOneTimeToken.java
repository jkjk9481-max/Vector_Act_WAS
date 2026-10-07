package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 비밀번호 재설정과 탈퇴 재인증에 사용하는 일회성 토큰의 DB 표현입니다.
 * 원문 토큰은 이 객체에 보관하지 않습니다. 유출 피해를 줄이기 위해 해시만 저장합니다.
 * A09는 REAUTH(5분), A11은 PASSWORD_RESET(15분)을 발급합니다.
 */
@Entity
@Table(name = "auth_one_time_tokens", indexes = {
        @Index(name = "idx_auth_one_time_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_auth_one_time_tokens_expires_at", columnList = "expires_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthOneTimeToken {
    // 토큰 자체의 식별자입니다. 회원 ID와 다르며 Hibernate가 저장 시 UUID를 생성합니다.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 토큰 소유자를 연결합니다. 탈퇴 시 본인에게 발급된 토큰인지 확인하는 데 필요합니다.
    // LAZY는 토큰만 읽을 때 회원 전체 정보를 즉시 읽어 오지 않게 합니다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_auth_one_time_tokens_user"))
    // 향후 회원의 실제 삭제 시 토큰도 정리됩니다. Soft Delete에서는 행이 남습니다.
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    // 용도를 문자열로 저장합니다. 다른 용도의 토큰을 탈퇴 인증에 사용할 수 없게 구분합니다.
    // Java 필드 tokenType을 DB의 token_type 컬럼에 명시적으로 연결합니다.
    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 30)
    private TokenType tokenType;

    // 명세의 VARCHAR(255)를 사용합니다. 현재 SHA-256 구현의 실제 해시 길이는 64자입니다.
    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime expiresAt;

    // null이면 아직 사용하지 않은 상태입니다. 소비 성공 시 한 번만 시각을 기록합니다.
    @Column(name = "used_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private OffsetDateTime createdAt;

    /** REAUTH 전용 생성 메서드: 같은 기준 시각으로 생성 시각과 5분 뒤 만료 시각을 정합니다. */
    public static AuthOneTimeToken createReauth(User user, String tokenHash, OffsetDateTime now) {
        var token = new AuthOneTimeToken();
        token.user = user;
        token.tokenType = TokenType.REAUTH;
        token.tokenHash = tokenHash;
        token.createdAt = now;
        token.expiresAt = now.plusMinutes(5);
        return token;
    }

    /** A11 전용 생성: 비밀번호를 바꾸거나 사용 처리하지 않고 15분 유효한 토큰을 만듭니다. */
    public static AuthOneTimeToken createPasswordReset(User user, String tokenHash, OffsetDateTime now) {
        var token = new AuthOneTimeToken();
        token.user = user;
        token.tokenType = TokenType.PASSWORD_RESET;
        token.tokenHash = tokenHash;
        token.createdAt = now;
        token.expiresAt = now.plusMinutes(15);
        // usedAt은 null(미사용)입니다. 실제 사용 검증과 소비는 향후 A12의 책임입니다.
        return token;
    }

    // JPA 저장 경로에서도 생성 시각이 비어 있지 않도록 하는 생명주기 콜백입니다.
    // 직접 SQL로 저장할 때는 PostgreSQL DDL의 DEFAULT now()가 같은 역할을 합니다.
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public enum TokenType { PASSWORD_RESET, REAUTH }
}
