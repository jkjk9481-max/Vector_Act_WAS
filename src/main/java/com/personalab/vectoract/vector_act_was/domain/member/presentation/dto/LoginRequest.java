package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotEmpty String password
) {
    public LoginRequest {
        // 회원가입 때와 동일한 이메일로 찾습니다. 비밀번호에는 strip/소문자 변환을 하지 않습니다.
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "LoginRequest[password=REDACTED]";
    }
}
