package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 응답에 필요한 값만 반환하여 비밀번호 해시가 외부로 노출되지 않도록 합니다. */
public record SignupResponse(UUID userId, String name, String email, OffsetDateTime createdAt) {
}
