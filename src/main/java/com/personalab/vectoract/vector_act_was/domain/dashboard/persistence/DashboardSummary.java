package com.personalab.vectoract.vector_act_was.domain.dashboard.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 주간·월간 기간 피드백(D05·D06 summary) 캐시입니다(DB 설계서 3.15 dashboard_summaries).
 * 문장은 AI 서버가 비동기로 생성해 채우며, 이 엔티티는 읽기만 하는 D05에서는 행이 없을 수 있습니다
 * (AI 서버 연동으로 생성 요청을 보내는 단계는 이 API의 범위 밖입니다).
 */
@Entity
@Table(name = "dashboard_summaries", uniqueConstraints = @UniqueConstraint(
        name = "uq_dashboard_summaries_period", columnNames = {"user_id", "period_type", "period_start"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DashboardSummary {
    public enum PeriodType { WEEKLY, MONTHLY }
    public enum Status { PENDING, READY, FAILED, INSUFFICIENT_DATA }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 10)
    private PeriodType periodType;

    @Column(name = "period_start", nullable = false, columnDefinition = "DATE")
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, columnDefinition = "DATE")
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "statistics_updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime statisticsUpdatedAt;

    @Column(name = "request_message_id")
    private UUID requestMessageId;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;
}
