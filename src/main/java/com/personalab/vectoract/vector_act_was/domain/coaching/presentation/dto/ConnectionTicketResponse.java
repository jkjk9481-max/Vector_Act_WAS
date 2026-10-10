package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import java.time.OffsetDateTime;

/**
 * C03 응답입니다.
 *
 * @param ticket       애플리케이션 서버가 서명한 JWT(aud=ai-server). 30초 유효, 1회만 사용 가능
 * @param expiresAt    티켓 만료 시각(JWT의 exp와 같은 값)
 * @param webSocketUrl 실시간 코칭 연결 기본 주소(같은 도메인의 WSS 주소, 토큰 없음).
 *                     클라이언트는 {@code {webSocketUrl}/ws/coaching-sessions/{sessionId}}로 연결합니다
 */
public record ConnectionTicketResponse(String ticket, OffsetDateTime expiresAt, String webSocketUrl) {
    // 티켓은 인증 수단이므로 응답 객체가 로그에 찍혀도 원문이 노출되지 않게 가립니다.
    @Override
    public String toString() { return "ConnectionTicketResponse[ticket=REDACTED]"; }
}
