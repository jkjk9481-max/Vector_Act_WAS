package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * C01 연습 세션 준비 요청 본문입니다.
 *
 * <p>길이(대본 1~20000자, 상황 1~2000자)는 DB CHECK와 같은 코드포인트 기준이라 Service에서 검사합니다.
 * (Bean Validation의 {@code @Size}는 UTF-16 단위로 세어 이모지 같은 문자를 2로 계산합니다.)
 *
 * @param scriptContent 연습할 대본 본문
 * @param situation     연기 상황 설명
 * @param coaching      코칭 설정. {@code @Valid}가 있어야 중첩 객체 내부의 검증 어노테이션도 실행됩니다
 */
public record CoachingSessionCreateRequest(
        @NotNull String scriptContent,
        @NotNull String situation,
        @NotNull @Valid CoachingConfig coaching) {}
