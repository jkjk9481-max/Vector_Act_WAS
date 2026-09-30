package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // SHA-256의 32바이트 결과를 64자리 16진수로 저장합니다. 원문을 저장할 필드는 없습니다.
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime expiresAt;

    // 폐기/교체는 A04, A05에서 사용할 자리입니다. 신규 로그인에서는 둘 다 null입니다.
    @Column(name = "revoked_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime revokedAt;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    public static RefreshToken create(User user, String tokenHash, OffsetDateTime issuedAt) {
        RefreshToken token = new RefreshToken();
        token.user = user;
        token.tokenHash = tokenHash;
        token.familyId = UUID.randomUUID(); // 새 로그인마다 별도의 토큰 계보를 시작합니다.
        token.issuedAt = issuedAt;
        token.expiresAt = issuedAt.plusDays(14);
        return token;
    }
}
