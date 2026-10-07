package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import java.time.OffsetDateTime;

/** A01 최종 응답: 토큰 문자열과 UTC 만료 시각만 공개합니다. 헤더명은 X-CSRF-TOKEN으로 고정입니다. */
public record CsrfResponse(String csrfToken, OffsetDateTime expiresAt) {
    @Override
    public String toString() {
        return "CsrfResponse[token=REDACTED]";
    }
}
