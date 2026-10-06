package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import java.time.OffsetDateTime;

/**
 * 성공 응답의 data에 들어갑니다. expiresAt은 UTC 오프셋을 포함한 날짜·시간 문자열로 직렬화됩니다.
 * reauthToken은 탈퇴용 일회성 토큰이며 Access Token이나 Refresh Token으로 사용할 수 없습니다.
 * 토큰 원문은 JSON 응답에는 필요하지만 디버그 로그에는 필요 없으므로 toString에서 가립니다.
 */
public record ReauthResponse(String reauthToken, OffsetDateTime expiresAt) {
    @Override public String toString() { return "ReauthResponse[token=REDACTED]"; }
}
