package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.SessionProgressResponse;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 세션 진행 상태에 대한 API를 담당합니다. C08(연습 취소)과 C09(세션 상태 조회)가 같은 응답 형식을 씁니다.
 */
@Service
public class SessionLifecycleService {
    private final UserRepository users;
    private final CoachingSessionRepository sessions;
    private final SessionProgressMapper progress;

    public SessionLifecycleService(UserRepository users, CoachingSessionRepository sessions,
                                   SessionProgressMapper progress) {
        this.users = users;
        this.sessions = sessions;
        this.progress = progress;
    }

    /**
     * C08 연습 취소. CREATED / RECORDING / FINALIZING 세션만 취소할 수 있습니다.
     * 이미 취소된 세션을 다시 취소해도 같은 결과를 돌려줍니다("반복 취소 허용").
     * 완료(COMPLETED)나 실패(FAILED)한 세션은 취소할 수 없어 409입니다.
     *
     * <p>취소하면 활성 세션이 아니게 되므로 회원은 새 세션을 준비(C01)할 수 있습니다.
     * 분석이 이미 진행 중(QUEUED/PROCESSING)이면 분석 상태도 CANCELED로 표시합니다.
     */
    @Transactional
    public SessionProgressResponse cancel(UUID userId, UUID sessionId) {
        // 같은 회원의 촬영 종료·취소 요청이 동시에 와도 상태 전이가 한 줄로 처리되게 합니다.
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        // 타인 소유·삭제된·없는 세션은 같은 404입니다.
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        switch (session.getStatus()) {
            case CREATED, RECORDING, FINALIZING -> session.cancel(OffsetDateTime.now(ZoneOffset.UTC));
            case CANCELED -> { } // 반복 취소: 상태를 바꾸지 않고 현재 상태를 응답합니다.
            default -> throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
        }
        return progress.toResponse(session);
    }

    /**
     * C09 세션 상태 조회. 읽기 전용이며 세션이 어떤 상태든 소유자는 조회할 수 있습니다.
     * 촬영 중 세션의 영상·분석 상태는 실시간 코칭 연결이 아니라 이 API로 확인합니다.
     * 타인 소유·삭제된·없는 세션은 구분되지 않는 같은 404입니다.
     */
    @Transactional(readOnly = true)
    public SessionProgressResponse get(UUID userId, UUID sessionId) {
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return progress.toResponse(session);
    }
}
