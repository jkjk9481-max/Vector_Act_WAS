package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface CoachingSessionRepository extends JpaRepository<CoachingSession, UUID> {
    /**
     * 세션 ID와 소유자를 함께 조건으로 조회합니다. 메서드 이름이 곧 쿼리입니다(Spring Data 파생 쿼리):
     * {@code WHERE id = ? AND user_id = ? AND deleted_at IS NULL}.
     * 다른 회원의 세션이나 삭제 표시된 세션은 "없음"과 같은 빈 결과가 되어, 호출한 쪽이
     * 세션의 존재 여부를 구분할 수 없습니다(소유권 확인을 조회 조건에 포함시키는 방식).
     */
    Optional<CoachingSession> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    // 삭제 표시되지 않은 활성(CREATED/RECORDING/FINALIZING) 세션이 있는지 확인합니다.
    boolean existsByUserIdAndDeletedAtIsNullAndStatusIn(UUID userId, Collection<CoachingSession.Status> statuses);
}
