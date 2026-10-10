package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SessionChunkRepository extends JpaRepository<SessionChunk, UUID> {
    /** (세션, 청크 번호)로 기존 예약을 찾습니다. DB UNIQUE 제약과 같은 조합이라 결과는 최대 1건입니다. */
    Optional<SessionChunk> findBySessionIdAndChunkIndex(UUID sessionId, int chunkIndex);
}
