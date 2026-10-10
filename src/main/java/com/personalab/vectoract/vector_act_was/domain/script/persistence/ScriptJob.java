package com.personalab.vectoract.vector_act_was.domain.script.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 이미지 OCR 작업(S01·S02)의 DB 표현입니다. 영구 대본 저장소가 아니며 접수 24시간 뒤 삭제됩니다.
 * 회원은 user_id 값으로만 참조합니다. 운영 DB는 FK ON DELETE CASCADE로 회원 삭제 시 함께 정리합니다.
 */
@Entity
@Table(name = "script_jobs", indexes = {
        @Index(name = "idx_script_jobs_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_script_jobs_expires", columnList = "expires_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScriptJob {
    public static final String JOB_TYPE_OCR = "OCR";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "job_type", nullable = false, length = 30)
    private String jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    // 완료 후 추출 본문입니다(1~20000자).
    @Column(name = "result_content", columnDefinition = "TEXT")
    private String resultContent;

    // 원본 이미지의 임시 저장 위치입니다. 처리 후 객체를 삭제하면 null로 비웁니다.
    @Column(name = "source_object_key", length = 700)
    private String sourceObjectKey;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "started_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime completedAt;

    /** 접수 시각 기준으로 24시간 뒤 만료되는 QUEUED 작업을 만듭니다. */
    public static ScriptJob createOcr(UUID userId, String sourceObjectKey, OffsetDateTime now) {
        var job = new ScriptJob();
        job.userId = userId;
        job.jobType = JOB_TYPE_OCR;
        job.status = Status.QUEUED;
        job.sourceObjectKey = sourceObjectKey;
        job.createdAt = now;
        job.expiresAt = now.plusHours(24);
        return job;
    }

    public void markProcessing(OffsetDateTime now) {
        this.status = Status.PROCESSING;
        this.startedAt = now;
    }

    public void complete(String content, OffsetDateTime now) {
        this.status = Status.COMPLETED;
        this.resultContent = content;
        this.failureCode = null;
        this.completedAt = now;
    }

    public void fail(String code, OffsetDateTime now) {
        this.status = Status.FAILED;
        this.failureCode = code;
        this.resultContent = null;
        this.completedAt = now;
    }

    public void clearSource() {
        this.sourceObjectKey = null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public enum Status { QUEUED, PROCESSING, COMPLETED, FAILED }
}
