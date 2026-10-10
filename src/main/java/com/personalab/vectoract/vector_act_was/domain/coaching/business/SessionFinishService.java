package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunkRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.SessionFinishRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.SessionProgressResponse;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKey;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * C07 촬영 종료·최종화 접수. 종료 선언(마지막 청크 번호·길이)을 기록하고 세션을 FINALIZING으로 바꿉니다.
 *
 * <p>이 단계는 "접수"까지만 합니다(응답 202). 청크를 하나의 영상으로 조립하고 분석을 요청하는 후속 처리는
 * 별도의 비동기 단계이며, 조립 방식과 AI 서버 전달 경로가 확정되지 않아 이 클래스에서는 시작하지 않습니다.
 * 누락 청크는 응답의 missingChunkIndexes로 알려 주고, 마감(업로드 15분) 안에 C04·C05로 보완하게 합니다.
 */
@Service
public class SessionFinishService {
    static final String SCOPE_FINISH = "COACHING_SESSION_FINISH";

    private final UserRepository users;
    private final CoachingSessionRepository sessions;
    private final SessionChunkRepository chunks;
    private final SessionProgressMapper progress;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final ObjectMapper objectMapper;
    private final long idempotencyTtlHours;

    public SessionFinishService(UserRepository users, CoachingSessionRepository sessions,
                                SessionChunkRepository chunks, SessionProgressMapper progress,
                                IdempotencyKeyRepository idempotencyKeys, ObjectMapper objectMapper,
                                @Value("${idempotency.ttl-hours:24}") long idempotencyTtlHours) {
        this.users = users;
        this.sessions = sessions;
        this.chunks = chunks;
        this.progress = progress;
        this.idempotencyKeys = idempotencyKeys;
        this.objectMapper = objectMapper;
        this.idempotencyTtlHours = idempotencyTtlHours;
    }

    /**
     * 처리 순서: 회원 잠금 → 멱등 기록(재시도 복원 / 키 충돌) → 세션 소유·상태 → 종료 선언 검증 → 기록.
     * 멱등 확인을 세션 검사보다 앞에 두는 이유는 C01과 같습니다. 성공했던 요청의 재시도가 상태 충돌(409)로
     * 바뀌지 않고 처음 응답을 그대로 받게 하기 위해서입니다.
     */
    @Transactional
    public SessionProgressResponse finish(UUID userId, UUID sessionId, UUID idempotencyKey,
                                          SessionFinishRequest request) {
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String hash = sessionId + ":" + request.lastChunkIndex() + ":" + request.durationMs();

        var existing = idempotencyKeys
                .findByUserIdAndIdempotencyKeyAndRequestScope(userId, idempotencyKey, SCOPE_FINISH).orElse(null);
        if (existing != null) {
            if (existing.getExpiresAt().isAfter(now)) {
                if (!existing.getRequestHash().equals(hash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                return objectMapper.readValue(existing.getResponseBody(), SessionProgressResponse.class);
            }
            // 만료된 기록은 같은 키를 새 요청으로 다시 쓸 수 있도록 지웁니다.
            idempotencyKeys.delete(existing);
            idempotencyKeys.flush();
        }

        // 타인 소유·삭제된·없는 세션은 같은 404입니다.
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        switch (session.getStatus()) {
            case RECORDING -> {
                // 이미 예약된 청크 중 선언한 마지막 번호보다 큰 번호가 있으면 선언이 실제 업로드와 모순입니다.
                boolean beyondDeclared = chunks.findBySessionIdOrderByChunkIndexAsc(sessionId).stream()
                        .anyMatch(c -> c.getChunkIndex() > request.lastChunkIndex());
                if (beyondDeclared) throw new BusinessException(ErrorCode.FINISH_MANIFEST_CONFLICT);
                session.finish(request.lastChunkIndex(), request.durationMs(), now);
            }
            case FINALIZING -> {
                // 이미 종료가 접수된 세션입니다. 최초 종료 정보는 바꿀 수 없으므로 같은 선언만 허용합니다.
                if (!session.sameFinishManifest(request.lastChunkIndex(), request.durationMs())) {
                    throw new BusinessException(ErrorCode.FINISH_MANIFEST_CONFLICT);
                }
            }
            // 촬영 시작 전이거나 이미 끝난(완료·실패·취소) 세션은 종료할 수 없습니다.
            default -> throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
        }

        var response = progress.toResponse(session);
        // 재시도 때 처음 응답을 그대로 복원할 수 있도록 응답 본문을 함께 저장합니다.
        idempotencyKeys.saveAndFlush(IdempotencyKey.create(userId, idempotencyKey, SCOPE_FINISH, hash,
                sessionId, 202, objectMapper.writeValueAsString(response), now,
                now.plusHours(idempotencyTtlHours)));
        return response;
    }
}
