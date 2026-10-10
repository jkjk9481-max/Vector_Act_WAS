package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import jakarta.validation.constraints.NotNull;

/**
 * 코칭 설정입니다. C01 요청(coaching)과 C01·C02 응답(coaching)에서 같은 모양으로 쓰입니다.
 *
 * <p>모든 필드가 필수라 래퍼 타입(Boolean)에 {@code @NotNull}을 붙였습니다. 원시 타입(boolean)을 쓰면 필드가
 * 누락돼도 false로 채워져 "누락"과 "false"를 구분할 수 없기 때문입니다.
 *
 * <p>{@code analysisOnly=true}이면 {@code visualEnabled}/{@code voiceEnabled}는 모두 false여야 한다는 규칙은
 * 필드 간 관계라서 어노테이션이 아니라 Service에서 검사합니다(COACHING_CONFIG_CONFLICT).
 *
 * @param visualEnabled 시각 코칭 사용 여부
 * @param voiceEnabled  음성 코칭 사용 여부
 * @param analysisOnly  분석 전용 여부(실시간 코칭 없이 사후 분석만)
 * @param intensity     코칭 강도 MINIMAL / NORMAL / INTENSIVE. 정의되지 않은 값은 JSON 변환 단계에서 400이 됩니다
 */
public record CoachingConfig(
        @NotNull Boolean visualEnabled,
        @NotNull Boolean voiceEnabled,
        @NotNull Boolean analysisOnly,
        @NotNull CoachingSession.Intensity intensity) {}
