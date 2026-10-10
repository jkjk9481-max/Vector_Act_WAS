package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * C04 요청 본문입니다. 업로드할 청크의 구간·크기·해시를 미리 선언합니다.
 *
 * <p>이 값들은 업로드 URL의 서명에 반영되고, 이후 C05가 실제 업로드된 객체와 대조해 검증합니다.
 * 래퍼 타입(Integer, Long)은 필드 누락을 0이 아닌 null로 구분해 검증에서 걸러내기 위해 씁니다.
 *
 * <p>sizeBytes의 상한(32MiB)은 형식 오류(400)가 아니라 "파일이 너무 큼(413)"으로 응답해야 하므로
 * 어노테이션이 아니라 Service에서 검사합니다. 그래서 int 범위를 넘는 값도 받을 수 있게 Long으로 둡니다.
 *
 * @param startMs   원본 영상 기준 청크 시작 시각(ms), 0 이상
 * @param endMs     청크 종료 시각(ms), startMs 초과 600000 이하 (startMs와의 관계는 Service에서 검사)
 * @param sizeBytes 청크 파일 크기(바이트), 1 이상 33554432(32MiB) 이하
 * @param sha256    파일의 SHA-256, 소문자 hex 64자리
 */
public record ChunkUploadUrlRequest(
        @NotNull @Min(0) Integer startMs,
        @NotNull @Min(1) @Max(600000) Integer endMs,
        @NotNull @Min(1) Long sizeBytes,
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {}
