package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import java.time.OffsetDateTime;

/**
 * 클라이언트에게 공개할 A10 응답 데이터입니다.
 * Service.Result는 내부 처리 결과, 이 DTO는 HTTP 응답 형식을 담당합니다.
 * 엔티티를 직접 반환하지 않으므로 이메일·비밀번호 해시 등이 응답에 섞이지 않습니다.
 */
public record WithdrawalResponse(OffsetDateTime deletedAt, OffsetDateTime purgeAt) {}
