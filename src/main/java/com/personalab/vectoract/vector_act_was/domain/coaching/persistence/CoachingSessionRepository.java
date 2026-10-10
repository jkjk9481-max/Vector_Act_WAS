package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.UUID;

public interface CoachingSessionRepository extends JpaRepository<CoachingSession, UUID> {
    // 삭제 표시되지 않은 활성(CREATED/RECORDING/FINALIZING) 세션이 있는지 확인합니다.
    boolean existsByUserIdAndDeletedAtIsNullAndStatusIn(UUID userId, Collection<CoachingSession.Status> statuses);
}
