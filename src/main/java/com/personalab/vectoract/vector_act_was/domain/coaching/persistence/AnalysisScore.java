package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 항목별 점수와 분석 가능 상태입니다(DB 설계서 3.12 analysis_scores). 분석 하나에 항목(category)마다 한 행입니다.
 * category는 EXPRESSION / VOICE / GAZE / POSTURE / EMOTION / SCRIPT_DELIVERY / SITUATION_FIT입니다.
 * POSTURE는 1차 범위에서 항상 NOT_SUPPORTED입니다.
 */
@Entity
@Table(name = "analysis_scores", uniqueConstraints = @UniqueConstraint(
        name = "uq_analysis_scores_category", columnNames = {"analysis_id", "category"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisScore {
    public enum Status { AVAILABLE, UNAVAILABLE, NOT_SUPPORTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(nullable = false, length = 40)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    // AVAILABLE일 때만 0~100 점수입니다.
    @Column(precision = 5, scale = 2)
    private BigDecimal score;

    // 모델 신뢰도 0~1. 모델이 제공하지 않으면 null입니다.
    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    // 분석 불가·미지원 사유 코드. 정상이면 null입니다.
    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    public static AnalysisScore of(UUID analysisId, String category, Status status, BigDecimal score,
                                   BigDecimal confidence, String reasonCode, OffsetDateTime now) {
        var row = new AnalysisScore();
        row.analysisId = analysisId;
        row.category = category;
        row.status = status;
        row.score = score;
        row.confidence = confidence;
        row.reasonCode = reasonCode;
        row.createdAt = now;
        return row;
    }
}
