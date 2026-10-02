package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A06 응답: JSON 필드명 createAt은 API 명세에 맞춥니다. */
public record MeResponse(UUID userId, String name, String email, OffsetDateTime createAt) {
}
