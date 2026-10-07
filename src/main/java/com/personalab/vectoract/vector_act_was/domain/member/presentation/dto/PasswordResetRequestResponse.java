package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

/** accepted는 가입 확인이나 메일 배달 완료가 아니라, 요청 처리/접수 확인입니다. */
public record PasswordResetRequestResponse(boolean accepted) {}
