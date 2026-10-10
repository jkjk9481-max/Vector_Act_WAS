package com.personalab.vectoract.vector_act_was.global.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Idempotency-Key를 쓰는 POST 요청의 재시도·충돌 처리 기록입니다(DB 설계서 3.14).
 * 같은 (회원, 키, 기능 범위)로 다시 오면 request_hash가 같을 때만 저장한 응답을 복원합니다.
 *
 * <p>멱등성(idempotency)이란: 같은 요청을 여러 번 보내도 결과가 한 번 보낸 것과 같다는 성질입니다.
 * 네트워크가 끊겨 응답을 못 받은 클라이언트가 같은 요청을 재시도해도 세션이 중복 생성되지 않게 하려고,
 * 클라이언트가 요청마다 만든 UUID(Idempotency-Key)와 처음 응답을 서버가 기억해 둡니다.
 *
 * <p>request_scope는 기능 구분자입니다(예: COACHING_SESSION_CREATE). 같은 키를 다른 API에 써도 충돌하지 않게
 * UNIQUE 제약에 포함됩니다.
 */
@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(name = "uq_idempotency_keys_scope",
                columnNames = {"user_id", "idempotency_key", "request_scope"}),
        indexes = @Index(name = "idx_idempotency_keys_expires", columnList = "expires_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "request_scope", nullable = false, length = 100)
    private String requestScope;

    // 요청 본문을 정규화해 SHA-256으로 만든 값입니다. 같은 키로 "다른 내용"이 오는 것을 감지하는 데 씁니다.
    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    // 최초 요청에서 만들어진 자원 ID입니다.
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "response_status")
    private Integer responseStatus;

    // 재요청 시 그대로 돌려줄 응답의 data 부분(JSON 문자열)입니다.
    // @JdbcTypeCode(JSON): 운영 PostgreSQL에서는 JSONB 컬럼에, H2 테스트에서는 JSON 컬럼에 매핑됩니다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    // 이 시각이 지나면 기록은 무효입니다. 같은 키가 다시 오면 지우고 새 요청으로 처리합니다.
    @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    public static IdempotencyKey create(UUID userId, UUID key, String scope, String requestHash,
                                        UUID resourceId, int responseStatus, String responseBody,
                                        OffsetDateTime now, OffsetDateTime expiresAt) {
        var record = new IdempotencyKey();
        record.userId = userId;
        record.idempotencyKey = key;
        record.requestScope = scope;
        record.requestHash = requestHash;
        record.resourceId = resourceId;
        record.responseStatus = responseStatus;
        record.responseBody = responseBody;
        record.createdAt = now;
        record.expiresAt = expiresAt;
        return record;
    }
}
