package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

/** A11은 이메일만 입력받습니다. 회원 ID나 새 비밀번호는 이 요청의 입력이 아닙니다. */
public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    public PasswordResetRequest {
        // 회원가입/로그인과 같은 정규화입니다. 대소문자나 양끝 공백으로 다른 회원을 찾지 않게 합니다.
        // 생성자 실행 후 @Valid가 검사하므로 공백뿐인 입력은 NotBlank 위반으로 거절됩니다.
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    // record의 기본 toString은 필드 값을 출력하므로 개인정보를 숨기는 형태로 바꿉니다.
    @Override public String toString() { return "PasswordResetRequest[email=REDACTED]"; }
}
