package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public record EmailChangeRequest(
        @NotBlank @Email @Size(max = 254) String newEmail,
        @NotNull @Size(min = 1) String currentPassword) {
    public EmailChangeRequest {
        newEmail = newEmail == null ? null : newEmail.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() { return "EmailChangeRequest[credentials=REDACTED]"; }
}
