package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

/** 기존 SignupResponse의 회원 정보 4개 필드를 재사용합니다. Refresh Token은 여기에 넣지 않습니다. */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, SignupResponse user) {
    @Override
    public String toString() {
        return "LoginResponse[tokens=REDACTED]";
    }
}
