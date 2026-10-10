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
 *
 * <p>상태 전이: {@code QUEUED → PROCESSING → COMPLETED | FAILED}. 전이 규칙은 아래 메서드에 모여 있고,
 * "지금 전이해도 되는 상태인지" 확인하는 책임은 {@code ScriptJobService}에 있습니다.
 */
@Entity
@Table(name = "script_jobs", indexes = {
        // 회원별 작업 조회용.
        @Index(name = "idx_script_jobs_user_created", columnList = "user_id, created_at"),
        // 만료 정리 스케줄러가 "만료된 작업"을 빠르게 찾기 위한 인덱스입니다.
        @Index(name = "idx_script_jobs_expires", columnList = "expires_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScriptJob {
    // job_type 컬럼 값. 현재는 이미지 OCR 한 종류만 있습니다.
    public static final String JOB_TYPE_OCR = "OCR";

    // UUID를 애플리케이션이 생성합니다. 순차 ID와 달리 추측할 수 없어 URL에 노출해도 안전합니다.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "job_type", nullable = false, length = 30)
    private String jobType;

    // EnumType.STRING: DB에 순서 번호가 아니라 이름("QUEUED")으로 저장합니다. enum 순서를 바꿔도 데이터가 깨지지 않습니다.
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

    // 이 시각 이후에는 조회가 410(RESULT_EXPIRED)이 되고, 정리 스케줄러가 행과 이미지를 삭제합니다.
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

    /** QUEUED → PROCESSING. 처리 스레드가 작업을 집어 든 시각을 기록합니다. */
    public void markProcessing(OffsetDateTime now) {
        this.status = Status.PROCESSING;
        this.startedAt = now;
    }

    /** PROCESSING → COMPLETED. 추출 본문을 저장하고 실패 코드는 비웁니다. */
    public void complete(String content, OffsetDateTime now) {
        this.status = Status.COMPLETED;
        this.resultContent = content;
        this.failureCode = null;
        this.completedAt = now;
    }

    /** → FAILED. 실패 코드를 기록하고 본문은 비웁니다(부분 결과를 노출하지 않습니다). */
    public void fail(String code, OffsetDateTime now) {
        this.status = Status.FAILED;
        this.failureCode = code;
        this.resultContent = null;
        this.completedAt = now;
    }

    /** 원본 이미지를 저장소에서 지운 뒤 호출해 key를 비웁니다. */
    public void clearSource() {
        this.sourceObjectKey = null;
    }

    // INSERT 직전에 호출되는 JPA 콜백입니다. 팩토리를 거치지 않고 저장되는 경우의 안전장치입니다.
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public enum Status { QUEUED, PROCESSING, COMPLETED, FAILED }
}
