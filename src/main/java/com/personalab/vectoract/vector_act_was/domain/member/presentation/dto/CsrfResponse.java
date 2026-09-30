package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

public record CsrfResponse(String token, String headerName) {
    @Override
    public String toString() {
        return "CsrfResponse[token=REDACTED]";
    }
}
