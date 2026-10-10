package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {
    /** 실시간 세션의 분석을 조회합니다. 세션당 최대 1건(UNIQUE)입니다. */
    Optional<Analysis> findBySessionId(UUID sessionId);
}
