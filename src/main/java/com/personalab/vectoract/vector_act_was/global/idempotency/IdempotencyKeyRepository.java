package com.personalab.vectoract.vector_act_was.global.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
    Optional<IdempotencyKey> findByUserIdAndIdempotencyKeyAndRequestScope(UUID userId, UUID key, String scope);
}
