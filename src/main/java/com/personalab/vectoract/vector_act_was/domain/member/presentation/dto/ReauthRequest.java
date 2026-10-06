package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A09 요청 JSON을 받는 DTO입니다. record는 생성자와 password() 접근자를 자동으로 만듭니다.
 * @NotNull은 필드 누락/null, @Size(min=1)은 빈 문자열을 거절합니다.
 * 기존 비밀번호를 확인하는 요청이므로 새 비밀번호의 길이 정책을 다시 강제하지 않습니다.
 * 공백도 실제 비밀번호의 일부일 수 있어 trim/strip하지 않고 그대로 비교합니다.
 */
public record ReauthRequest(@NotNull @Size(min = 1) String password) {
    // 요청 객체를 로그에 출력해도 평문 비밀번호가 표시되지 않도록 합니다.
    @Override public String toString() { return "ReauthRequest[password=REDACTED]"; }
}
