package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 연습(코칭) 세션의 DB 표현입니다(DB 설계서 3.6). C01은 설정만 저장하고, 촬영·분석 단계의 컬럼은
 * 이후 API(C02~)가 채웁니다. 회원은 user_id 값으로만 참조하며 운영 DB는 FK ON DELETE CASCADE입니다.
 * 회원당 활성 세션 1개는 운영 DB의 partial unique index와 Service 검사로 함께 보장합니다.
 */
@Entity
@Table(name = "coaching_sessions", indexes = {
        @Index(name = "idx_coaching_sessions_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_coaching_sessions_user_status", columnList = "user_id, status")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CoachingSession {
    /** 회원당 1개만 허용하는 활성 상태입니다. */
    public static final List<Status> ACTIVE_STATUSES = List.of(Status.CREATED, Status.RECORDING, Status.FINALIZING);

    public enum Status { CREATED, RECORDING, FINALIZING, COMPLETED, FAILED, CANCELED }
    public enum VideoStatus { NOT_STARTED, UPLOADING, ASSEMBLING, READY, FAILED, EXPIRED, DELETED }
    public enum AnalysisStatus { NOT_STARTED, QUEUED, PROCESSING, COMPLETED, PARTIAL, FAILED, CANCELED }
    public enum AnalysisMode { REALTIME, NEAR_REALTIME, POST_ONLY }
    public enum Intensity { MINIMAL, NORMAL, INTENSIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "script_content", nullable = false, columnDefinition = "TEXT")
    private String scriptContent;

    @Column(name = "situation", nullable = false, columnDefinition = "TEXT")
    private String situation;

    @Column(name = "visual_enabled", nullable = false)
    private boolean visualEnabled;

    @Column(name = "voice_enabled", nullable = false)
    private boolean voiceEnabled;

    @Column(name = "analysis_only", nullable = false)
    private boolean analysisOnly;

    @Enumerated(EnumType.STRING)
    @Column(name = "coaching_intensity", nullable = false, length = 20)
    private Intensity coachingIntensity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_status", nullable = false, length = 20)
    private VideoStatus videoStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 20)
    private AnalysisStatus analysisStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_mode", nullable = false, length = 20)
    private AnalysisMode analysisMode;

    @Column(name = "started_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime startedAt;

    @Column(name = "ended_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime endedAt;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "video_expires_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime videoExpiresAt;

    @Column(name = "upload_deadline_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime uploadDeadlineAt;

    @Column(name = "analysis_attempt", nullable = false)
    private int analysisAttempt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "deletion_status", length = 20)
    private String deletionStatus;

    @Column(name = "deleted_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime deletedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;

    /**
     * C02: 촬영 시작을 접수합니다. 상태 전이 규칙을 엔티티 한곳에 모아 둔 메서드입니다.
     * <ul>
     *   <li>status: CREATED → RECORDING (활성 세션 상태이므로 회원당 1개 규칙은 그대로 유지됩니다)</li>
     *   <li>videoStatus: NOT_STARTED → UPLOADING (촬영 중 Chunk가 올라오는 단계. 명세에 값이 없어 정한 값)</li>
     *   <li>startedAt: 서버가 start를 접수한 시각(클라이언트 시각을 신뢰하지 않습니다)</li>
     *   <li>videoExpiresAt: 시작 시각 + 30일. 이 시각 이후 원본 영상이 삭제 대상입니다(DB 설계서 삭제 정책)</li>
     * </ul>
     * 호출 전에 상태가 CREATED인지 확인하는 책임은 Service에 있습니다.
     */
    public void startRecording(OffsetDateTime now) {
        this.status = Status.RECORDING;
        this.videoStatus = VideoStatus.UPLOADING;
        this.startedAt = now;
        this.videoExpiresAt = now.plusDays(30);
        this.updatedAt = now;
    }

    /** C01: CREATED 상태의 세션을 만듭니다. 촬영 관련 값은 모두 시작 전 상태입니다. */
    public static CoachingSession prepare(UUID userId, String scriptContent, String situation,
                                          boolean visualEnabled, boolean voiceEnabled, boolean analysisOnly,
                                          Intensity intensity, OffsetDateTime now) {
        var session = new CoachingSession();
        session.userId = userId;
        session.scriptContent = scriptContent;
        session.situation = situation;
        session.visualEnabled = visualEnabled;
        session.voiceEnabled = voiceEnabled;
        session.analysisOnly = analysisOnly;
        session.coachingIntensity = intensity;
        session.status = Status.CREATED;
        session.videoStatus = VideoStatus.NOT_STARTED;
        session.analysisStatus = AnalysisStatus.NOT_STARTED;
        session.analysisMode = AnalysisMode.REALTIME;
        session.createdAt = now;
        session.updatedAt = now;
        return session;
    }
}
