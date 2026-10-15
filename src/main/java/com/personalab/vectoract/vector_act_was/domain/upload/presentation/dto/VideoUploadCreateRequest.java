package com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * U01 대용량 업로드 시작 요청 본문입니다.
 *
 * <p>fileName 길이(1~255자)와 contentType 허용값, sizeBytes 범위(1~2147483648)는 DB CHECK와 같은
 * 코드포인트/범위 기준이라 Service에서 검사합니다({@link VideoUploadResponse} 주변 Service 참고).
 *
 * @param fileName    원본 파일명(저장 key에 그대로 쓰지 않음)
 * @param contentType video/mp4 또는 video/webm
 * @param sizeBytes   파일 전체 크기(바이트)
 */
public record VideoUploadCreateRequest(
        @NotBlank String fileName,
        @NotBlank String contentType,
        @NotNull Long sizeBytes) {}
