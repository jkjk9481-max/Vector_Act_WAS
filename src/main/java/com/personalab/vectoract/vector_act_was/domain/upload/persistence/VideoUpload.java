package com.personalab.vectoract.vector_act_was.domain.upload.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 기존 영상 대용량 업로드 요청·검증 상태입니다(DB 설계서 3.9 video_uploads).
 * 흐름: U01(이 행 생성, INITIATED) → U02(Part URL 발급) → 클라이언트가 Part를 S3에 직접 PUT →
 * U03(완료 접수, VERIFYING → READY/FAILED) → U06(READY 업로드만 분석 접수 가능).
 */
@Entity
@Table(name = "video_uploads", indexes = {
        @Index(name = "idx_video_uploads_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_video_uploads_expires", columnList = "expires_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoUpload {
    /** DB CHECK와 같은 지원 Content-Type입니다. */
    public static final long MAX_SIZE_BYTES = 2_147_483_648L; // 2GiB
    public static final int PART_SIZE_BYTES = 16 * 1024 * 1024; // 16MiB(고정)

    public enum Status { INITIATED, UPLOADING, VERIFYING, READY, FAILED, CANCELED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    // S3 CreateMultipartUpload가 돌려준 업로드 ID입니다. API의 uploadId(이 행의 id)와는 다릅니다.
    @Column(name = "s3_upload_id", length = 255)
    private String s3UploadId;

    // 임시 업로드 원본의 object key입니다. 조립(최종화)되지 않은 조각이 모이는 자리입니다.
    @Column(name = "object_key", unique = true, length = 700)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "part_size_bytes", nullable = false)
    private int partSizeBytes;

    @Column(name = "part_count", nullable = false)
    private int partCount;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime expiresAt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "completed_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime completedAt;

    @Column(name = "deleted_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime deletedAt;

    /** U01: 새 업로드를 접수합니다. partCount는 ceil(sizeBytes / PART_SIZE_BYTES)입니다. */
    public static VideoUpload initiate(UUID userId, String fileName, String contentType, long sizeBytes,
                                       String s3UploadId, String objectKey, OffsetDateTime now) {
        var upload = new VideoUpload();
        upload.userId = userId;
        upload.fileName = fileName;
        upload.contentType = contentType;
        upload.sizeBytes = sizeBytes;
        upload.s3UploadId = s3UploadId;
        upload.objectKey = objectKey;
        upload.status = Status.INITIATED;
        upload.partSizeBytes = PART_SIZE_BYTES;
        upload.partCount = (int) Math.ceil(sizeBytes / (double) PART_SIZE_BYTES);
        upload.createdAt = now;
        upload.expiresAt = now.plusHours(24);
        return upload;
    }

    /** U02: 첫 Part URL 발급 시 INITIATED → UPLOADING으로 바꿉니다. 이미 UPLOADING이면 아무 일도 하지 않습니다. */
    public void beginUploading() {
        if (status == Status.INITIATED) status = Status.UPLOADING;
    }

    /** U03: 완료 접수를 받아 검증 중 상태로 전환합니다. */
    public void beginVerifying() {
        this.status = Status.VERIFYING;
    }

    /** U03: 검증이 끝나 실제 업로드된 크기·길이를 확정하고 READY로 바꿉니다. */
    public void ready(Integer durationMs, OffsetDateTime now) {
        this.status = Status.READY;
        this.durationMs = durationMs;
        this.completedAt = now;
    }

    /** U03/분석 단계: 검증·분석 실패를 기록합니다. */
    public void fail(String failureCode, OffsetDateTime now) {
        this.status = Status.FAILED;
        this.failureCode = failureCode;
        this.completedAt = now;
    }

    /**
     * U05: 업로드를 취소합니다. "분석 접수 전만 취소 가능"은 이 업로드를 가리키는 분석(analyses)이
     * 있는지로 판단하며(UPLOAD_IN_USE), video_uploads 자체의 상태로는 막지 않습니다.
     * 호출 책임은 Service에 있습니다.
     */
    public void cancel(OffsetDateTime now) {
        this.status = Status.CANCELED;
        this.completedAt = now;
    }
}
