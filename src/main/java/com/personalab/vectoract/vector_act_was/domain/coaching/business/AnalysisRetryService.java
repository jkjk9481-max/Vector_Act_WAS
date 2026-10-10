package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.AnalysisRetryResponse;
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
 * C11 실패한 분석 재접수. 분석이 실패했고 원본 영상이 남아 있을 때만, 총 3회 시도 안에서 같은 분석을
 * 다시 대기(QUEUED) 상태로 돌립니다. 재분석은 새 분석 행이 아니라 같은 행의 시도 번호를 올리는 방식입니다.
 *
 * <p>이 클래스는 "재접수"(상태 전이와 시도 번호 증가)까지만 합니다. AI 서버에 분석 요청 메시지를 발행하는
 * 단계는 전달 경로가 확정되지 않아 구현하지 않았습니다.
 */
@Service
public class AnalysisRetryService {
    static final String SCOPE_RETRY = "COACHING_ANALYSIS_RETRY";
    /** 총 시도 횟수 상한(최초 분석 포함). API 명세서 C11: 총 3회까지. */
    static final int MAX_ATTEMPTS = 3;

    private final UserRepository users;
    private final CoachingSessionRepository sessions;
    private final AnalysisRepository analyses;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final ObjectMapper objectMapper;
    private final long idempotencyTtlHours;

    public AnalysisRetryService(UserRepository users, CoachingSessionRepository sessions,
                                AnalysisRepository analyses, IdempotencyKeyRepository idempotencyKeys,
                                ObjectMapper objectMapper,
                                @Value("${idempotency.ttl-hours:24}") long idempotencyTtlHours) {
        this.users = users;
        this.sessions = sessions;
        this.analyses = analyses;
        this.idempotencyKeys = idempotencyKeys;
        this.objectMapper = objectMapper;
        this.idempotencyTtlHours = idempotencyTtlHours;
    }

    /**
     * 처리 순서: 회원 잠금 → 멱등 기록(재시도 복원 / 키 충돌) → 세션 소유 → 분석·영상 상태 판단 → 재접수.
     * 상태 판단 순서: 이미 진행 중(409) → 영상 만료(410) → 재시도 불가 조건(409).
     */
    @Transactional
    public AnalysisRetryResponse retry(UUID userId, UUID sessionId, UUID idempotencyKey) {
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // 본문이 없는 요청이라 "같은 요청"의 기준은 대상 세션입니다. 다른 세션에 같은 키를 쓰면 충돌입니다.
        String hash = sessionId.toString();

        var existing = idempotencyKeys
                .findByUserIdAndIdempotencyKeyAndRequestScope(userId, idempotencyKey, SCOPE_RETRY).orElse(null);
        if (existing != null) {
            if (existing.getExpiresAt().isAfter(now)) {
                if (!existing.getRequestHash().equals(hash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                // 같은 요청의 재시도: 다시 시도 번호를 올리지 않고 처음 응답을 돌려줍니다.
                return objectMapper.readValue(existing.getResponseBody(), AnalysisRetryResponse.class);
            }
            idempotencyKeys.delete(existing);
            idempotencyKeys.flush();
        }

        // 타인 소유·삭제된·없는 세션은 같은 404입니다.
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var analysis = analyses.findBySessionId(sessionId).orElse(null);

        // 이미 대기·처리 중이면 새로 접수할 수 없습니다.
        if (analysis != null && (analysis.getStatus() == Analysis.Status.QUEUED
                || analysis.getStatus() == Analysis.Status.PROCESSING)) {
            throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
        }
        // 원본 영상이 만료·삭제됐으면 다시 분석할 수 없습니다(410).
        if (session.getVideoStatus() == CoachingSession.VideoStatus.EXPIRED
                || session.getVideoStatus() == CoachingSession.VideoStatus.DELETED) {
            throw new BusinessException(ErrorCode.VIDEO_EXPIRED);
        }
        // 분석이 FAILED이고 원본이 READY이며 시도 횟수가 남아 있을 때만 허용합니다.
        boolean allowed = analysis != null && analysis.getStatus() == Analysis.Status.FAILED
                && session.getVideoStatus() == CoachingSession.VideoStatus.READY
                && analysis.getAttempt() < MAX_ATTEMPTS;
        if (!allowed) throw new BusinessException(ErrorCode.RETRY_NOT_ALLOWED);

        analysis.retry(now);
        session.queueAnalysisRetry(analysis.getAttempt(), now);
        var response = new AnalysisRetryResponse(analysis.getAttempt(), Analysis.Status.QUEUED.name());
        idempotencyKeys.saveAndFlush(IdempotencyKey.create(userId, idempotencyKey, SCOPE_RETRY, hash, sessionId,
                202, objectMapper.writeValueAsString(response), now, now.plusHours(idempotencyTtlHours)));
        return response;
    }
}
