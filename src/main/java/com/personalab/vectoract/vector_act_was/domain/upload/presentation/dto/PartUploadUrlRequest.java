package com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * U02 Part 업로드 URL 발급 요청 본문입니다.
 *
 * @param sha256    해당 Part의 SHA-256(소문자 hex 64자)
 * @param sizeBytes 해당 Part의 크기(마지막 Part만 나머지 크기)
 */
public record PartUploadUrlRequest(
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String sha256,
        @NotNull @Min(1) Long sizeBytes) {}
