package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SessionVideoRepository extends JpaRepository<SessionVideo, UUID> {
    /** 세션의 영상 메타데이터를 조회합니다. 세션당 최대 1건(UNIQUE)입니다. */
    Optional<SessionVideo> findBySessionId(UUID sessionId);
}
