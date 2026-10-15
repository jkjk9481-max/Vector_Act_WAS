package com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * U06 업로드 영상 분석 접수 요청 본문입니다.
 * scriptContent(1~20000자)·situation(1~2000자) 길이는 DB CHECK와 같은 코드포인트 기준이라 Service에서 검사합니다
 * (C01의 CoachingSessionCreateRequest와 동일한 이유).
 *
 * @param uploadId      READY이고 본인 소유이며 아직 분석에 쓰이지 않은 업로드
 * @param scriptContent 연습한 대본 본문
 * @param situation     연기 상황 설명
 */
public record UploadAnalysisCreateRequest(
        @NotNull UUID uploadId,
        @NotNull String scriptContent,
        @NotNull String situation) {}
