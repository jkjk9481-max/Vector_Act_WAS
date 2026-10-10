package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * C08 연습 취소 요청 본문입니다.
 *
 * @param reason 취소 사유. 정의된 값이 아니면 JSON 변환 단계에서 400이 됩니다
 */
public record SessionCancelRequest(@NotNull Reason reason) {
    /** 사용자가 직접 취소 / 장치 오류 / 네트워크 오류. */
    public enum Reason { USER_REQUEST, DEVICE_ERROR, NETWORK_ERROR }
}
