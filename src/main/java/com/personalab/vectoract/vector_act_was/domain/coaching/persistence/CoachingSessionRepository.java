package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
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

    /**
     * D01: 본인의 연습 기록 목록입니다. 삭제 표시된 세션과 {@code excludedStatus}(CANCELED)는 제외합니다.
     * from/to는 둘 다 null이면 전체 기간입니다. from은 포함(>=), to는 그 다음날 0시 미포함(<)으로 계산해 넘깁니다.
     */
    @Query("SELECT s FROM CoachingSession s WHERE s.userId = :userId AND s.deletedAt IS NULL "
            + "AND s.status <> :excludedStatus "
            + "AND (:from IS NULL OR s.createdAt >= :from) AND (:to IS NULL OR s.createdAt < :to)")
    Page<CoachingSession> findForList(@Param("userId") UUID userId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("excludedStatus") CoachingSession.Status excludedStatus,
            Pageable pageable);
}
