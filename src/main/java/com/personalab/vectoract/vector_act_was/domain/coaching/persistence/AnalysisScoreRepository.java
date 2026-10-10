package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AnalysisScoreRepository extends JpaRepository<AnalysisScore, UUID> {
    /** 분석에 속한 항목별 점수를 모두 조회합니다(최대 7개). */
    List<AnalysisScore> findByAnalysisId(UUID analysisId);
}
