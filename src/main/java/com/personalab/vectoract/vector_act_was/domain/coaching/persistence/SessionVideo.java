package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 실시간 촬영 영상의 메타데이터입니다(DB 설계서 3.8 session_videos). 세션당 최대 1개입니다.
 *
 * <p>왜 coaching_sessions와 분리했는가: 세션은 "연습 한 번"의 상태이고, 영상은 "저장소에 올라간 파일"의
 * 정보입니다. 원본 영상은 30일 뒤 삭제되지만 세션·분석 결과는 남아야 하므로(DB 설계서) 수명이 다른 데이터를
 * 테이블로 나눴습니다.
 *
 * <p>단계별로 채워지는 컬럼:
 * <ul>
 *   <li>C02(촬영 시작): input_content_type, width, height, frame_rate, created_at, expires_at</li>
 *   <li>이후 Chunk 업로드·조립 단계: stored_content_type, object_key, size_bytes, duration_ms</li>
 *   <li>삭제 단계: deleted_at (S3 원본 삭제가 끝난 시각)</li>
 * </ul>
 * 그래서 C02 시점에는 nullable 컬럼이 대부분 null입니다.
 */
@Entity
@Table(name = "session_videos", uniqueConstraints = {
        // 세션당 영상은 최대 1개입니다. 같은 세션에 두 번 INSERT하면 DB가 거부합니다.
        @UniqueConstraint(name = "uq_session_videos_session", columnNames = "session_id"),
        // 같은 S3 객체를 두 행이 가리키지 못하게 합니다(NULL은 여러 개 허용).
        @UniqueConstraint(name = "uq_session_videos_object_key", columnNames = "object_key")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionVideo {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 세션은 FK 연관관계 대신 ID 값으로만 참조합니다(프로젝트 공통 방식). 운영 DB에는 ON DELETE CASCADE FK가 있습니다.
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    // 촬영 시작 시 클라이언트가 알려 준 입력 MIME (정식 표기로 정규화한 값).
    @Column(name = "input_content_type", length = 100)
    private String inputContentType;

    // 서버가 조립해 저장한 최종 재생 파일의 MIME(기본 video/mp4). 조립 단계에서 채웁니다.
    @Column(name = "stored_content_type", length = 100)
    private String storedContentType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    // NUMERIC(5,2): 전체 5자리 중 소수 2자리. 29.97 같은 값을 오차 없이 저장합니다.
    @Column(name = "frame_rate", precision = 5, scale = 2)
    private BigDecimal frameRate;

    // S3 객체 key. DB에는 파일 자체가 아니라 key만 저장합니다. 조립이 끝나기 전에는 null입니다.
    @Column(name = "object_key", length = 700)
    private String objectKey;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    // 검증한 실제 영상 길이(0~600000ms = 최대 10분).
    @Column(name = "duration_ms")
    private Integer durationMs;

    // columnDefinition = "TIMESTAMPTZ": 시간대 정보를 보존하는 타입으로 지정합니다(운영 PostgreSQL 기준).
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    // 원본 영상 삭제 시점(시작 + 30일). 삭제 스케줄러가 이 시각을 기준으로 대상을 찾습니다.
    @Column(name = "expires_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime expiresAt;

    @Column(name = "deleted_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime deletedAt;

    /**
     * 촬영 시작 시점의 영상 정보를 가진 행을 만듭니다. 생성자를 막고 정적 팩토리를 쓰는 이유는
     * "어떤 상태의 객체를 만드는지"를 메서드 이름({@code started})으로 드러내기 위해서입니다.
     * frameRate는 호출하는 쪽에서 소수 둘째 자리로 맞춰 넘깁니다.
     */
    public static SessionVideo started(UUID sessionId, String inputContentType, int width, int height,
                                       BigDecimal frameRate, OffsetDateTime now, OffsetDateTime expiresAt) {
        var video = new SessionVideo();
        video.sessionId = sessionId;
        video.inputContentType = inputContentType;
        video.width = width;
        video.height = height;
        video.frameRate = frameRate;
        video.createdAt = now;
        video.expiresAt = expiresAt;
        return video;
    }
}
