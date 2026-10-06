package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

/**
 * A08 성공 응답의 data에 들어가는 DTO입니다. JSON에서는 {"accepted":true}로 표현됩니다.
 * 이 API는 저장과 토큰 폐기의 커밋까지 완료된 뒤 true를 반환하므로 비동기 대기 상태가 아닙니다.
 * 비밀번호, 해시, 새 토큰은 응답하지 않습니다. 바깥 success/message/error는 ApiResponse가 만듭니다.
 */
public record ChangePasswordResponse(boolean accepted) { }
