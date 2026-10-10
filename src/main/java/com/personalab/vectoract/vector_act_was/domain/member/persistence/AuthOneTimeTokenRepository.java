package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

// Spring Data JPA가 이 인터페이스의 DB 접근 구현체를 만듭니다.
// <AuthOneTimeToken, UUID>는 관리할 엔티티와 그 기본 키의 타입입니다.
public interface AuthOneTimeTokenRepository extends JpaRepository<AuthOneTimeToken, UUID> {
    // 다른 재인증/비밀번호 재설정 토큰도 탈퇴와 함께 사용 불가로 만듭니다.
    // used_at이 없는 행만 갱신하여 이미 사용된 토큰의 최초 사용 시각은 덮어쓰지 않습니다.
    // flushAutomatically는 User 변경 등 대기 SQL을 먼저 보냅니다. 별도 커밋하지는 않습니다.
    // 벌크 UPDATE는 이미 메모리에 로드한 토큰 객체를 바꾸지 않으므로 이후 그 객체의 usedAt을 읽지 않습니다.
    @Modifying(flushAutomatically = true)
    @Query("update AuthOneTimeToken t set t.usedAt = :now where t.user.id = :userId and t.usedAt is null")
    int invalidateAll(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    Optional<AuthOneTimeToken> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true)
    @Query("""
            update AuthOneTimeToken t set t.usedAt = :now
            where t.user.id = :userId and t.tokenType = :tokenType and t.usedAt is null
            """)
    int invalidateByUserAndType(@Param("userId") UUID userId,
                                @Param("tokenType") AuthOneTimeToken.TokenType tokenType,
                                @Param("now") OffsetDateTime now);

    /**
     * 토큰 검사와 사용 처리를 하나의 조건부 UPDATE로 실행합니다.
     * 먼저 조회하고 나중에 사용 표시만 하면 두 요청이 동시에 '미사용'으로 판단할 수 있습니다.
     * 이 쿼리는 소유자·용도·만료·미사용 조건을 모두 만족하는 행만 변경하므로 한 번만 성공합니다.
     * 반환값은 바뀐 행 수입니다. 1이면 성공, 0이면 사용할 수 없는 토큰입니다.
     * Service의 기존 트랜잭션에 참여하며, Repository에서 별도 커밋하지 않습니다.
     */
    // @Modifying은 SELECT가 아닌 변경 쿼리임을 알립니다.
    // flushAutomatically는 이미 대기 중인 엔티티 변경을 쿼리 실행 전에 DB로 보냅니다.
    // 벌크 UPDATE는 메모리에 로드된 엔티티를 갱신하지 않으므로 소비 후 기존 객체의 usedAt을 읽지 않습니다.
    @Modifying(flushAutomatically = true)
    @Query("""
            update AuthOneTimeToken t set t.usedAt = :now
            where t.tokenHash = :hash and t.user.id = :userId and t.tokenType = :tokenType
              and t.usedAt is null and t.expiresAt > :now
            """)
    int consumeIfUsable(@Param("hash") String hash, @Param("userId") UUID userId,
                        @Param("tokenType") AuthOneTimeToken.TokenType tokenType, @Param("now") OffsetDateTime now);
    // Hard Delete에서 부모 users 행보다 먼저 자식 데이터를 제거합니다.
    // Spring Data가 메서드 이름을 해석하여 user.id가 일치하는 토큰을 삭제합니다.
    // Soft Delete에서는 호출하지 않습니다. purge 서비스의 트랜잭션에 참여하므로 실패하면 함께 롤백됩니다.
    void deleteByUserId(UUID userId);
}
