package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.*;

public record EmailChangeCompletionRequest(@NotBlank @Size(max = 255) String changeToken) {
    @Override
    public String toString() { return "EmailChangeCompletionRequest[token=REDACTED]"; }
}
