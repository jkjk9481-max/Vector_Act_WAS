package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * C03 연결 티켓 발급. 촬영 중인 세션의 소유자에게 AI 서버 WebSocket 접속용 일회용 티켓을 줍니다.
 *
 * <p>이 API는 DB를 바꾸지 않습니다(조회만 하고 JWT를 서명해 돌려줍니다). 티켓의 "1회 사용" 보장은 AI 서버가
 * jti를 기억해 처리하므로 애플리케이션 서버는 발급 기록을 저장하지 않습니다. 재연결 때마다 새 티켓을 발급합니다.
 */
@Service
public class ConnectionTicketService {
    private final CoachingSessionRepository sessions;
    private final ConnectionTicketSigner signer;
    // 클라이언트가 접속할 WSS 기본 주소(같은 도메인의 주소, 경로 제외). 환경마다 달라 설정으로 받습니다.
    private final String webSocketUrl;

    public ConnectionTicketService(CoachingSessionRepository sessions, ConnectionTicketSigner signer,
            @Value("${coaching.connection-ticket.web-socket-url:}") String webSocketUrl) {
        this.sessions = sessions;
        this.signer = signer;
        this.webSocketUrl = webSocketUrl == null ? "" : webSocketUrl.strip();
    }

    /** 컨트롤러가 응답으로 바꿀 결과 값입니다. */
    public record Issued(String ticket, OffsetDateTime expiresAt, String webSocketUrl) {
        // 티켓 원문이 로그에 찍히지 않게 가립니다.
        @Override
        public String toString() { return "Issued[ticket=REDACTED]"; }
    }

    /**
     * 오류 검사 순서: 소유·존재(404) → 세션 상태(409) → 서버 설정(503).
     * 클라이언트가 고칠 수 있는 문제를 먼저 알려 주고, 서버 쪽 설정 문제는 마지막에 판단합니다.
     */
    @Transactional(readOnly = true)
    public Issued issue(UUID userId, UUID sessionId) {
        // 타인 소유·삭제된·없는 세션은 모두 같은 404입니다(존재 여부를 숨깁니다).
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 실시간 코칭 연결은 촬영 중(RECORDING)에만 의미가 있습니다. 시작 전이거나 종료된 세션은 거절합니다.
        // (명세는 상태 조건을 명시하지 않아 AI 서버 설계서 7장의 "촬영 중 연결·재연결"을 기준으로 정했습니다.)
        if (session.getStatus() != CoachingSession.Status.RECORDING) {
            throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
        }
        // 서명 키나 접속 주소가 설정되지 않은 서버는 티켓을 만들 수 없습니다. 일시적 장애와 같은 503으로 알립니다.
        if (!signer.isConfigured() || webSocketUrl.isEmpty()) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        var ticket = signer.issue(userId, sessionId, Instant.now());
        return new Issued(ticket.token(), OffsetDateTime.ofInstant(ticket.expiresAt(), ZoneOffset.UTC),
                webSocketUrl);
    }
}
