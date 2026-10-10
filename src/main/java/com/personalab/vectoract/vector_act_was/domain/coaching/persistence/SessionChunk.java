package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 실시간 촬영 중 순차 업로드되는 영상 청크의 DB 표현입니다(DB 설계서 3.7 session_chunks).
 * 클라이언트는 5~10초 단위로 영상을 잘라 청크마다 "업로드 URL 발급(C04) → 파일 PUT → 검증 완료(C05)" 순서로 올립니다.
 *
 * <p>상태: {@code RESERVED}(C04가 업로드 자리를 예약함) → {@code VERIFIED}(C05가 크기·해시를 검증함).
 * 세션당 청크 번호(0~120)는 UNIQUE라, 같은 번호는 하나의 행만 존재합니다.
 */
@Entity
@Table(name = "session_chunks", uniqueConstraints = {
        // 세션 안에서 청크 번호는 한 번만 쓸 수 있습니다. 같은 번호의 재요청은 새 행이 아니라 기존 행을 다시 봅니다.
        @UniqueConstraint(name = "uq_session_chunks_session_index", columnNames = {"session_id", "chunk_index"}),
        // 같은 S3 객체를 두 행이 가리키지 못하게 합니다.
        @UniqueConstraint(name = "uq_session_chunks_object_key", columnNames = "object_key")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionChunk {
    public enum Status { RESERVED, VERIFIED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 소속 세션. FK 연관관계 대신 ID 값으로만 참조합니다(운영 DB는 ON DELETE CASCADE).
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    // 원본 영상 기준 시작·종료 시각(ms)입니다. 클라이언트가 선언한 값입니다.
    @Column(name = "start_ms", nullable = false)
    private int startMs;

    @Column(name = "end_ms", nullable = false)
    private int endMs;

    // 클라이언트가 선언한 청크 크기와 SHA-256입니다. C05가 실제 객체와 비교해 검증합니다.
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String sha256;

    // S3 staging(임시) 객체 key입니다. 최종 영상이 조립되면 정리됩니다.
    @Column(name = "object_key", nullable = false, length = 700)
    private String objectKey;

    // 미완료 청크 정리 기준 시각입니다. 업로드 URL의 유효 시간이 끝나는 시점으로 둡니다.
    @Column(name = "expires_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "verified_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime verifiedAt;

    /** C04: 업로드 자리를 예약합니다(RESERVED). */
    public static SessionChunk reserve(UUID sessionId, int chunkIndex, int startMs, int endMs, long sizeBytes,
                                       String sha256, String objectKey, OffsetDateTime now, OffsetDateTime expiresAt) {
        var chunk = new SessionChunk();
        chunk.sessionId = sessionId;
        chunk.chunkIndex = chunkIndex;
        chunk.status = Status.RESERVED;
        chunk.startMs = startMs;
        chunk.endMs = endMs;
        chunk.sizeBytes = sizeBytes;
        chunk.sha256 = sha256;
        chunk.objectKey = objectKey;
        chunk.createdAt = now;
        chunk.expiresAt = expiresAt;
        return chunk;
    }

    /** 같은 내용으로 업로드 URL을 다시 발급할 때 정리 기준 시각을 연장합니다. */
    public void extendReservation(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** 선언한 내용(구간·크기·해시)이 기존 예약과 모두 같은지 확인합니다. 하나라도 다르면 충돌입니다. */
    public boolean sameContent(int startMs, int endMs, long sizeBytes, String sha256) {
        return this.startMs == startMs && this.endMs == endMs
                && this.sizeBytes == sizeBytes && this.sha256.equals(sha256);
    }
}
