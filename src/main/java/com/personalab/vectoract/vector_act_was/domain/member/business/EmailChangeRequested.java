package com.personalab.vectoract.vector_act_was.domain.member.business;

import java.time.OffsetDateTime;

public record EmailChangeRequested(String newEmail, String rawToken, OffsetDateTime expiresAt) {
    @Override
    public String toString() { return "EmailChangeRequested[credentials=REDACTED]"; }
}
