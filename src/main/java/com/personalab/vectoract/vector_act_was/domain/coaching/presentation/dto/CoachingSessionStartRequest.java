package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * C02 촬영 시작 요청 본문입니다. 브라우저 MediaRecorder가 실제로 사용하는 입력 영상 정보를 서버에 알립니다.
 *
 * <p>모든 필드가 필수라서 {@code @NotNull}을 붙였습니다. 래퍼 타입(Integer, BigDecimal)을 쓰는 이유는
 * 필드가 누락됐을 때 기본값 0이 아니라 null이 되어 검증에서 걸러지게 하기 위해서입니다.
 *
 * <p>검증 실패는 컨트롤러에서 400 VALIDATION_ERROR가 됩니다. 단, mimeType의 "지원 여부"는
 * 형식 오류(400)와 구분해 415로 응답해야 하므로 어노테이션이 아니라 Service에서 검사합니다.
 *
 * @param mimeType  입력 MIME. {@code video/webm;codecs=vp8,opus} 또는
 *                  {@code video/mp4;codecs=avc1.42E01E,mp4a.40.2}만 지원
 * @param width     영상 너비(px), 1~1920
 * @param height    영상 높이(px), 1~1080
 * @param frameRate 초당 프레임 수, 1~30. 29.97처럼 소수가 올 수 있어 BigDecimal로 받습니다
 *                  (double은 이진 부동소수점이라 30.00 같은 경계값 비교가 부정확할 수 있습니다)
 */
public record CoachingSessionStartRequest(
        @NotNull String mimeType,
        @NotNull @Min(1) @Max(1920) Integer width,
        @NotNull @Min(1) @Max(1080) Integer height,
        @NotNull @DecimalMin("1") @DecimalMax("30") BigDecimal frameRate) {}
