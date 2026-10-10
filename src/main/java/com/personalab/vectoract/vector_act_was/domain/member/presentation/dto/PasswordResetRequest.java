package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    public PasswordResetRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() { return "PasswordResetRequest[email=REDACTED]"; }
}
