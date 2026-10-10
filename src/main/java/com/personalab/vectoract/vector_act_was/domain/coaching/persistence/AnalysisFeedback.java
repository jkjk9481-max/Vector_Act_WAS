package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 영상 시간축과 연결되는 구간 피드백입니다(DB 설계서 3.13 analysis_feedbacks). id가 API의 feedbackId입니다.
 * subcategory는 category에 대응하는 세부 코드만 허용됩니다(운영 DB CHECK).
 */
@Entity
@Table(name = "analysis_feedbacks", indexes = @Index(
        name = "idx_analysis_feedbacks_timeline", columnList = "analysis_id, start_ms"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "start_ms", nullable = false)
    private int startMs;

    @Column(name = "end_ms", nullable = false)
    private int endMs;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, length = 40)
    private String subcategory;

    // STRENGTH / IMPROVEMENT
    @Column(nullable = false, length = 20)
    private String kind;

    // INFO / WARNING
    @Column(nullable = false, length = 20)
    private String severity;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(length = 500)
    private String suggestion;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    public static AnalysisFeedback of(UUID analysisId, int startMs, int endMs, String category, String subcategory,
                                      String kind, String severity, String message, String suggestion,
                                      OffsetDateTime now) {
        var row = new AnalysisFeedback();
        row.analysisId = analysisId;
        row.startMs = startMs;
        row.endMs = endMs;
        row.category = category;
        row.subcategory = subcategory;
        row.kind = kind;
        row.severity = severity;
        row.message = message;
        row.suggestion = suggestion;
        row.createdAt = now;
        return row;
    }
}
