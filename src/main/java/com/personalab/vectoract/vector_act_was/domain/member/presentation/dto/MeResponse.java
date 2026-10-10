package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A06·A07·A15·A16 공통 응답 DTO: 클라이언트에 전달할 데이터만 담는 객체입니다.
 * userId: 회원 식별자. name: 이름(1~30자), email: 이메일(최대 254자).
 * createdAt: 가입 시각(UTC 시차 포함).
 * profileImageUrl: 5분 유효 Presigned GET URL. 이미지가 없으면 null이며 필드는 항상 포함됩니다.
 */
public record MeResponse(UUID userId, String name, String email, OffsetDateTime createdAt, String profileImageUrl) {
}
