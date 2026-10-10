package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 길이(대본 1~20000자, 상황 1~2000자)는 DB CHECK와 같은 코드포인트 기준이라 Service에서 검사합니다. */
public record CoachingSessionCreateRequest(
        @NotNull String scriptContent,
        @NotNull String situation,
        @NotNull @Valid CoachingConfig coaching) {}
