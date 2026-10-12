package com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto;

import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUpload;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * U01(생성)·U03(완료)·U04(조회)·U05(취소)가 공통으로 쓰는 업로드 상태 응답입니다.
 * uploadId는 앱이 만든 이 업로드의 식별자이며 S3 Multipart Upload ID(s3UploadId)와는 다릅니다.
 */
public record VideoUploadResponse(
        UUID uploadId, String status, int partSizeBytes, int partCount,
        OffsetDateTime expiresAt, String failureCode) {

    public static VideoUploadResponse of(VideoUpload upload) {
        return new VideoUploadResponse(upload.getId(), upload.getStatus().name(), upload.getPartSizeBytes(),
                upload.getPartCount(), upload.getExpiresAt(), upload.getFailureCode());
    }
}
