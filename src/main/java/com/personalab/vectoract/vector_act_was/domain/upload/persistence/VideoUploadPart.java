package com.personalab.vectoract.vector_act_was.domain.upload.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 대용량 Multipart Upload의 Part 하나입니다(DB 설계서 3.10 video_upload_parts).
 * U02가 Part를 예약(RESERVED)하고, U03이 S3 ETag와 함께 UPLOADED로 확정합니다.
 */
@Entity
@Table(name = "video_upload_parts",
        uniqueConstraints = @UniqueConstraint(name = "uq_video_upload_parts_number",
                columnNames = {"upload_id", "part_number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoUploadPart {
    public enum Status { RESERVED, UPLOADED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "upload_id", nullable = false)
    private UUID uploadId;

    @Column(name = "part_number", nullable = false)
    private int partNumber;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(length = 255)
    private String etag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "uploaded_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime uploadedAt;

    /** U02: Part 하나를 예약합니다. 같은 번호에 같은 내용으로 재요청하면 URL만 다시 발급합니다(ChunkUploadService와 동일 패턴). */
    public static VideoUploadPart reserve(UUID uploadId, int partNumber, long sizeBytes, String sha256,
                                          OffsetDateTime now) {
        var part = new VideoUploadPart();
        part.uploadId = uploadId;
        part.partNumber = partNumber;
        part.sizeBytes = sizeBytes;
        part.sha256 = sha256;
        part.status = Status.RESERVED;
        part.createdAt = now;
        return part;
    }

    /** 같은 Part 번호에 대해 선언한 크기·해시가 이전 요청과 같은지 확인합니다(재시도 허용 기준). */
    public boolean sameContent(long sizeBytes, String sha256) {
        return this.sizeBytes == sizeBytes && this.sha256.equals(sha256);
    }

    /** U03: 클라이언트가 제출한 ETag로 업로드 완료를 확정합니다. */
    public void markUploaded(String etag, OffsetDateTime now) {
        this.etag = etag;
        this.status = Status.UPLOADED;
        this.uploadedAt = now;
    }
}
