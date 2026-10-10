package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.*;

/**
 * A14 요청 본문입니다.
 *
 * @param changeToken A13 메일 링크에 담긴 일회용 토큰 원문. 공백만 있는 값은 거절하고(@NotBlank),
 *                    비정상적으로 긴 입력은 해시 계산 전에 막습니다(@Size). 정상 토큰은 Base64URL 43자입니다.
 */
public record EmailChangeCompletionRequest(@NotBlank @Size(max = 255) String changeToken) {
    // 요청 객체가 로그나 예외 메시지에 출력되어도 토큰 원문이 노출되지 않게 가립니다.
    @Override
    public String toString() { return "EmailChangeCompletionRequest[token=REDACTED]"; }
}
