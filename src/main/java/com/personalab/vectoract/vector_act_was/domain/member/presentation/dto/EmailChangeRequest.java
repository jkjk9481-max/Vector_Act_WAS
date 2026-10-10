package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

/**
 * A13 요청 본문입니다.
 *
 * @param newEmail        바꾸려는 새 이메일. 형식(@Email)과 최대 254자를 검증합니다(RFC 5321 주소 길이 한계)
 * @param currentPassword 본인 확인용 현재 비밀번호. 형식 규칙을 걸지 않는 이유는 가입 당시 규칙과 무관하게
 *                        "저장된 해시와 일치하는지"만 보면 되기 때문입니다
 */
public record EmailChangeRequest(
        @NotBlank @Email @Size(max = 254) String newEmail,
        @NotNull @Size(min = 1) String currentPassword) {
    // record의 compact 생성자: 필드에 대입되기 전에 값을 정규화합니다. 공백 제거 + 소문자 변환.
    // (검증 어노테이션은 정규화된 값을 기준으로 실행됩니다.)
    public EmailChangeRequest {
        newEmail = newEmail == null ? null : newEmail.strip().toLowerCase(Locale.ROOT);
    }

    // 로그나 예외 메시지에 비밀번호가 출력되지 않도록 가립니다.
    @Override
    public String toString() { return "EmailChangeRequest[credentials=REDACTED]"; }
}
