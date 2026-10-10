package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AnalysisFeedbackRepository extends JpaRepository<AnalysisFeedback, UUID> {
    /** 시간순 피드백을 최대 300개까지 읽습니다(API 명세서 C10: segments 최대 300개). */
    List<AnalysisFeedback> findTop300ByAnalysisIdOrderByStartMsAscCreatedAtAsc(UUID analysisId);
}
