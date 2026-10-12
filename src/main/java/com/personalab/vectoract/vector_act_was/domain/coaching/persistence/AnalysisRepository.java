package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {
    /** 실시간 세션의 분석을 조회합니다. 세션당 최대 1건(UNIQUE)입니다. */
    Optional<Analysis> findBySessionId(UUID sessionId);

    /** 업로드 영상 분석을 조회합니다. 업로드당 최대 1건(UNIQUE)입니다. U05가 "분석 접수 전만 취소 가능"을 확인할 때도 씁니다. */
    Optional<Analysis> findByUploadId(UUID uploadId);

    /** U07~U09: analysisId와 소유자로 조회합니다. 타인 소유·없는 분석은 같은 404로 처리하기 위해 조건에 포함합니다. */
    Optional<Analysis> findByIdAndUserId(UUID id, UUID userId);
}
