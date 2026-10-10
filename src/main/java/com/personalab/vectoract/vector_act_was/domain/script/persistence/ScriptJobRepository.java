package com.personalab.vectoract.vector_act_was.domain.script.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScriptJobRepository extends JpaRepository<ScriptJob, UUID> {
    // 다른 회원의 작업은 존재 여부도 알 수 없도록 소유자 조건을 함께 사용합니다.
    Optional<ScriptJob> findByIdAndUserId(UUID id, UUID userId);

    // 상태 전이는 같은 작업을 동시에 처리하지 못하도록 행을 잠그고 수행합니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ScriptJob j where j.id = :id")
    Optional<ScriptJob> lockById(@Param("id") UUID id);

    // 정리 대상 ID만 가져옵니다. 각 작업은 Service가 잠근 뒤 다시 확인합니다.
    @Query("select j.id from ScriptJob j where j.expiresAt <= :now order by j.expiresAt, j.id")
    List<UUID> findExpiredIds(@Param("now") OffsetDateTime now);
}
