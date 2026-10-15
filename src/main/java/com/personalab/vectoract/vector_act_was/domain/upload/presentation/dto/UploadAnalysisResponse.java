package com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import java.time.OffsetDateTime;
import java.util.UUID;

/** U06(접수)·U07(상태조회)·U09(취소)가 공통으로 쓰는 업로드 분석 상태 응답입니다. */
public record UploadAnalysisResponse(UUID analysisId, String status, OffsetDateTime expiresAt, String failureCode) {

    public static UploadAnalysisResponse of(Analysis analysis) {
        return new UploadAnalysisResponse(analysis.getId(), analysis.getStatus().name(),
                analysis.getExpiresAt(), analysis.getFailureCode());
    }
}
