package com.personalab.vectoract.vector_act_was.domain.member.business;

import java.time.OffsetDateTime;

/** DB 커밋 뒤 발송 계층에 전달할 내부 이벤트입니다. DB 엔티티나 HTTP 응답이 아닙니다. */
public record PasswordResetRequested(String email, String rawToken, OffsetDateTime expiresAt) {
    @Override public String toString() { return "PasswordResetRequested[email=REDACTED, token=REDACTED]"; }
}
