package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Lock a stable row before reading tokens, serializing rotation and reuse detection
    // even when requests present different generations of the same family.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = (select t.user.id from RefreshToken t where t.tokenHash = :hash)")
    Optional<User> lockOwnerByTokenHash(@Param("hash") String hash);

    List<RefreshToken> findByFamilyIdAndRevokedAtIsNull(UUID familyId);

    // A08: 메서드 이름을 해석해 Spring Data JPA가 조회 쿼리를 만듭니다.
    // UserId는 토큰이 참조하는 user의 id, And는 조건의 결합, RevokedAtIsNull은 미폐기를 뜻합니다.
    // 특정 family가 아니라 회원 전체 범위이므로 여러 기기에서 로그인한 토큰을 모두 조회합니다.
    // 만료 여부는 조건에 없어서 만료됐더라도 아직 미폐기인 행은 함께 반환됩니다.
    // 이 메서드는 조회만 하며, Service가 각 엔티티에 revoke()를 호출해야 실제 변경이 생깁니다.
    // 사용자 행 잠금과 같은 Service 트랜잭션에 참여하므로 여기에 별도 @Transactional을 붙이지 않습니다.
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
    // Hard Delete에서 부모 users 행보다 먼저 자식 데이터를 제거합니다.
    void deleteByUserId(UUID userId);
}
