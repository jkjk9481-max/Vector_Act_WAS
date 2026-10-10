package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * AI 분석 결과 본체입니다(DB 설계서 3.11 analyses). 세션당 최대 1개이며, 재분석은 같은 행의 attempt를 올립니다.
 * 항목별 점수는 {@link AnalysisScore}, 구간별 피드백은 {@link AnalysisFeedback}에 있습니다.
 *
 * <p>이 행은 분석 요청·결과 수신 단계(AI 서버 연동)가 만들고 채웁니다. C10 결과 조회는 읽기만 합니다.
 * 상태 전이 메서드({@link #queued}, {@link #complete}, {@link #fail})는 그 단계와 테스트가 사용합니다.
 * 이 엔티티는 실시간(LIVE_CAPTURE) 조회에 필요한 컬럼만 매핑합니다(업로드 분석의 upload_id 등은 제외).
 */
@Entity
@Table(name = "analyses", uniqueConstraints = @UniqueConstraint(name = "uq_analyses_session", columnNames = "session_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Analysis {
    public static final String SOURCE_LIVE_CAPTURE = "LIVE_CAPTURE";

    public enum Status { QUEUED, PROCESSING, COMPLETED, PARTIAL, FAILED, CANCELED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // LIVE_CAPTURE / UPLOADED_VIDEO
    @Column(nullable = false, length = 30)
    private String source;

    // 실시간 분석이면 소속 세션 ID입니다. 세션당 분석은 최대 1개(UNIQUE)입니다.
    @Column(name = "session_id")
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    // 논리 분석의 현재 시도 번호(1~3). 재분석(C11)이 올립니다.
    @Column(nullable = false)
    private int attempt;

    @Column(name = "schema_version", length = 30)
    private String schemaVersion;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "scoring_version", length = 100)
    private String scoringVersion;

    // 분석 기준 영상 길이(검증된 값, 0~600000ms).
    @Column(name = "duration_ms")
    private Integer durationMs;

    // 유효한 핵심 점수 평균(0~100). 평균을 낼 점수가 없으면 null입니다.
    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(columnDefinition = "TEXT")
    private String summary;

    // 강점·개선점·다음 연습 추천: 문자열 배열을 JSON으로 저장합니다.
    @JdbcTypeCode(SqlTypes.JSON)
    private String strengths;

    @JdbcTypeCode(SqlTypes.JSON)
    private String improvements;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "next_practice")
    private String nextPractice;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "generated_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime generatedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;

    /** 실시간 세션의 분석을 접수한 상태(QUEUED, 시도 1)로 만듭니다. */
    public static Analysis queued(UUID userId, UUID sessionId, OffsetDateTime now) {
        var analysis = new Analysis();
        analysis.userId = userId;
        analysis.source = SOURCE_LIVE_CAPTURE;
        analysis.sessionId = sessionId;
        analysis.status = Status.QUEUED;
        analysis.attempt = 1;
        analysis.createdAt = now;
        analysis.updatedAt = now;
        return analysis;
    }

    /** 결과를 받아 COMPLETED 또는 PARTIAL로 확정합니다. 배열 값은 JSON 문자열로 넘깁니다. */
    public void complete(Status finalStatus, String schemaVersion, String modelVersion, String scoringVersion,
                         int durationMs, BigDecimal overallScore, String summary, String strengthsJson,
                         String improvementsJson, String nextPracticeJson, OffsetDateTime now) {
        this.status = finalStatus;
        this.schemaVersion = schemaVersion;
        this.modelVersion = modelVersion;
        this.scoringVersion = scoringVersion;
        this.durationMs = durationMs;
        this.overallScore = overallScore;
        this.summary = summary;
        this.strengths = strengthsJson;
        this.improvements = improvementsJson;
        this.nextPractice = nextPracticeJson;
        this.failureCode = null;
        this.generatedAt = now;
        this.updatedAt = now;
    }

    /** 분석 실패를 기록합니다. */
    public void fail(String failureCode, OffsetDateTime now) {
        this.status = Status.FAILED;
        this.failureCode = failureCode;
        this.updatedAt = now;
    }
}
