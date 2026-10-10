package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * C07 촬영 종료 요청 본문입니다. 클라이언트가 "청크를 몇 번까지 만들었고 영상 길이가 얼마인지"를 선언합니다.
 * 서버는 이 선언을 기준으로 누락된 청크를 계산하며, 미디어 검증 전의 신고값이라 그대로 신뢰하지 않습니다.
 *
 * @param lastChunkIndex 마지막 청크 번호. 0 이상 120 이하(청크 번호 범위와 동일)
 * @param durationMs     클라이언트가 신고한 영상 길이(ms). 1 이상 600000 이하(최대 10분)
 */
public record SessionFinishRequest(
        @NotNull @Min(0) @Max(120) Integer lastChunkIndex,
        @NotNull @Min(1) @Max(600000) Integer durationMs) {}
