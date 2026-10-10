package com.personalab.vectoract.vector_act_was.global.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
    /**
     * (회원, 멱등 키, 기능 범위)로 기존 기록을 찾습니다. 메서드 이름이 곧 쿼리입니다:
     * {@code WHERE user_id = ? AND idempotency_key = ? AND request_scope = ?}.
     * 이 세 값의 조합은 DB UNIQUE 제약과 같아, 결과는 최대 1건입니다.
     * 회원 ID를 조건에 포함하므로 다른 회원이 우연히 같은 키를 써도 서로의 기록을 볼 수 없습니다.
     */
    Optional<IdempotencyKey> findByUserIdAndIdempotencyKeyAndRequestScope(UUID userId, UUID key, String scope);
}
